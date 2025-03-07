package me.fzzyhmstrs.tridents_n_stuff.entity

import me.fzzyhmstrs.gear_core.modifier_util.EquipmentModifierHelper
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LightningEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.PersistentProjectileEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.ToolMaterial
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

open class CustomTridentEntity : PersistentProjectileEntity {
    protected var tridentStack = ItemStack.EMPTY.copy()
    private var dealtDamage = false
    private var returnTimer = 0
    private var damage = 8f

    constructor(entityType: EntityType<out CustomTridentEntity?>?, world: World?) : super(entityType, world)
    constructor(entityType: EntityType<out CustomTridentEntity?>?,world: World?, owner: LivingEntity?, stack: ItemStack) : super(
        entityType,
        owner,
        world
    ) {
        tridentStack = stack.copy()
        dataTracker.set(LOYALTY, EnchantmentHelper.getLoyalty(stack).toByte())
        dataTracker.set(ENCHANTED, stack.hasGlint())
    }

    fun setDamage(material:ToolMaterial) {
        this.damage = material.attackDamage
    }

    override fun initDataTracker() {
        super.initDataTracker()
        dataTracker.startTracking(LOYALTY, 0.toByte())
        dataTracker.startTracking(ENCHANTED, false)
    }

    override fun tick() {
        if (inGroundTime > 4) {
            dealtDamage = true
        }
        val entity = owner
        val i = dataTracker.get(LOYALTY)
        if (i > 0 && (dealtDamage || this.isNoClip) && entity != null) {
            if (!isOwnerAlive) {
                if (!world.isClient && pickupType == PickupPermission.ALLOWED) {
                    this.dropStack(asItemStack(), 0.1f)
                }
                discard()
            } else {
                this.isNoClip = true
                val vec3d = entity.eyePos.subtract(pos)
                setPos(this.x, this.y + vec3d.y * 0.015 * i.toDouble(), this.z)
                if (world.isClient) {
                    lastRenderY = this.y
                }
                val d = 0.05 * i.toDouble()
                velocity = velocity.multiply(0.95).add(vec3d.normalize().multiply(d))
                if (returnTimer == 0) {
                    playSound(SoundEvents.ITEM_TRIDENT_RETURN, 10.0f, 1.0f)
                }
                ++returnTimer
            }
        }
        super.tick()
    }

    private val isOwnerAlive: Boolean
        get() {
            val entity = owner
            return if (entity == null || !entity.isAlive) {
                false
            } else entity !is ServerPlayerEntity || !entity.isSpectator()
        }

    override fun asItemStack(): ItemStack {
        return tridentStack.copy()
    }

    val isEnchanted: Boolean
        get() = dataTracker.get(ENCHANTED)

    override fun getEntityCollision(currentPosition: Vec3d, nextPosition: Vec3d): EntityHitResult? {
        return if (dealtDamage) {
            null
        } else super.getEntityCollision(currentPosition, nextPosition)
    }

    open fun onOwnedHit(owner: LivingEntity, target: LivingEntity, source: DamageSource, amount: Float): Float{
        var newAmount = amount
        for (equipMod in EquipmentModifierHelper.getRelevantModifiers(owner,this.tridentStack)){
            newAmount = equipMod.onAttack(this.tridentStack,owner,target,source, newAmount)
            equipMod.postHit(this.tridentStack, owner, target)
        }
        return newAmount
    }

    open fun onOwnedKill(owner: LivingEntity, target: LivingEntity) {
        for (equipMod in EquipmentModifierHelper.getRelevantModifiers(owner,this.tridentStack)){
            equipMod.killedOther(this.tridentStack, owner, target)
        }
    }

    override fun onEntityHit(entityHitResult: EntityHitResult) {
        val blockPos: BlockPos?
        val entity = entityHitResult.entity
        var f = this.damage
        val livingEntity: Entity? = owner
        val damageSource = this.damageSources.trident(this, if (owner == null) this else livingEntity)
        if (entity is LivingEntity) {
            f += EnchantmentHelper.getAttackDamage(tridentStack, entity.group)
            f = if (livingEntity is LivingEntity) {
                onOwnedHit(livingEntity, entity, damageSource, f)
            } else {
                f
            }
        }
        
        dealtDamage = true
        var soundEvent = SoundEvents.ITEM_TRIDENT_HIT
        if (entity.damage(damageSource, f)) {
            if (entity.type === EntityType.ENDERMAN) {
                return
            }
            if (entity is LivingEntity) {
                if (livingEntity is LivingEntity) {
                    EnchantmentHelper.onUserDamaged(entity, livingEntity)
                    EnchantmentHelper.onTargetDamaged(livingEntity, entity)
                    if (entity.isDead)
                        onOwnedKill(livingEntity,entity)
                }
                onHit(entity)
            }
        }
        velocity = velocity.multiply(-0.01, -0.1, -0.01)
        var volume = 1.0f
        if (world is ServerWorld && world.isThundering && hasChanneling() && world.isSkyVisible(entity.blockPos)) {
            blockPos = entity.blockPos
            val le = LightningEntity(EntityType.LIGHTNING_BOLT,world)
            //val lightningEntity = EntityType.LIGHTNING_BOLT.create(world)
            le.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(blockPos))
            le.channeler =
                if (livingEntity is ServerPlayerEntity) livingEntity else null
            world.spawnEntity(le)
            soundEvent = SoundEvents.ITEM_TRIDENT_THUNDER
            volume = 5.0f
        }
        playSound(soundEvent, volume, 1.0f)
    }

    private fun hasChanneling(): Boolean {
        return EnchantmentHelper.hasChanneling(tridentStack)
    }

    override fun tryPickup(player: PlayerEntity): Boolean {
        return super.tryPickup(player) || this.isNoClip && isOwner(player) && player.inventory.insertStack(
            asItemStack()
        )
    }

    override fun getHitSound(): SoundEvent {
        return SoundEvents.ITEM_TRIDENT_HIT_GROUND
    }

    override fun onPlayerCollision(player: PlayerEntity) {
        if (isOwner(player) || owner == null) {
            super.onPlayerCollision(player)
        }
    }

    override fun readCustomDataFromNbt(nbt: NbtCompound) {
        super.readCustomDataFromNbt(nbt)
        if (nbt.contains("Trident", 10)) {
            tridentStack = ItemStack.fromNbt(nbt.getCompound("Trident"))
        }
        dealtDamage = nbt.getBoolean("DealtDamage")
        dataTracker.set(LOYALTY, EnchantmentHelper.getLoyalty(tridentStack).toByte())
    }

    override fun writeCustomDataToNbt(nbt: NbtCompound) {
        super.writeCustomDataToNbt(nbt)
        nbt.put("Trident", tridentStack.writeNbt(NbtCompound()))
        nbt.putBoolean("DealtDamage", dealtDamage)
    }

    public override fun age() {
        val i = dataTracker.get(LOYALTY)
        if (pickupType != PickupPermission.ALLOWED || i <= 0) {
            super.age()
        }
    }

    override fun getDragInWater(): Float {
        return 0.99f
    }

    override fun shouldRender(cameraX: Double, cameraY: Double, cameraZ: Double): Boolean {
        return true
    }

    companion object {
        private val LOYALTY = DataTracker.registerData(CustomTridentEntity::class.java, TrackedDataHandlerRegistry.BYTE)
        private val ENCHANTED =
            DataTracker.registerData(CustomTridentEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
    }
}
