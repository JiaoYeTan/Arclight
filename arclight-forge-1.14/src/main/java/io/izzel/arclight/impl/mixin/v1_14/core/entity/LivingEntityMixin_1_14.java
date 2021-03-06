package io.izzel.arclight.impl.mixin.v1_14.core.entity;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.datafixers.util.Either;
import io.izzel.arclight.common.bridge.entity.LivingEntityBridge;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.attributes.IAttribute;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.item.ExperienceOrbEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.potion.Effect;
import net.minecraft.potion.EffectInstance;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.PotionEvent;
import org.bukkit.craftbukkit.v.event.CraftEventFactory;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin_1_14 extends EntityMixin_1_14 implements LivingEntityBridge {

    // @formatter:off
    @Shadow @Final private static DataParameter<Integer> POTION_EFFECTS;
    @Shadow @Final private static DataParameter<Boolean> HIDE_PARTICLES;
    @Shadow @Final public Map<Effect, EffectInstance> activePotionsMap;
    @Shadow public boolean potionsNeedUpdate;
    @Shadow protected abstract void onFinishedPotionEffect(EffectInstance effect);
    @Shadow protected abstract void onChangedPotionEffect(EffectInstance id, boolean reapply);
    @Shadow protected abstract void updatePotionMetadata();
    @Shadow public abstract boolean removePotionEffect(Effect effectIn);
    @Shadow public abstract boolean isPotionApplicable(EffectInstance potioneffectIn);
    @Shadow protected abstract void onNewPotionEffect(EffectInstance id);
    @Shadow @Nullable public abstract EffectInstance removeActivePotionEffect(@Nullable Effect potioneffectin);
    @Shadow public int deathTime;
    @Shadow protected abstract boolean isPlayer();
    @Shadow protected int recentlyHit;
    @Shadow protected abstract boolean canDropLoot();
    @Shadow public abstract void heal(float healAmount);
    @Shadow public abstract boolean canEntityBeSeen(Entity entityIn);
    @Shadow public abstract float getHealth();
    @Shadow public abstract float getMaxHealth();
    @Shadow public abstract boolean isSleeping();
    @Shadow public abstract Collection<EffectInstance> getActivePotionEffects();
    @Shadow public abstract IAttributeInstance getAttribute(IAttribute attribute);
    // @formatter:on

    /**
     * @author IzzelAliz
     * @reason
     */
    @SuppressWarnings("ConstantConditions")
    @Overwrite
    protected void onDeathUpdate() {
        ++this.deathTime;
        if (this.deathTime >= 20 && !this.removed) {

            if (!this.world.isRemote && (this.isPlayer() || this.recentlyHit > 0 && this.canDropLoot() && this.world.getGameRules().getBoolean(GameRules.DO_MOB_LOOT))) {
                int i = this.bridge$getExpToDrop();
                while (i > 0) {
                    int j = ExperienceOrbEntity.getXPSplit(i);
                    i -= j;
                    this.world.addEntity(new ExperienceOrbEntity(this.world, this.posX, this.posY, this.posZ, j));
                }
                this.bridge$setExpToDrop(0);
            }

            this.remove((Object) this instanceof ServerPlayerEntity); //Forge keep data until we revive player

            for (int k = 0; k < 20; ++k) {
                double d2 = this.rand.nextGaussian() * 0.02D;
                double d0 = this.rand.nextGaussian() * 0.02D;
                double d1 = this.rand.nextGaussian() * 0.02D;
                this.world.addParticle(ParticleTypes.POOF, this.posX + (double) (this.rand.nextFloat() * this.getWidth() * 2.0F) - (double) this.getWidth(), this.posY + (double) (this.rand.nextFloat() * this.getHeight()), this.posZ + (double) (this.rand.nextFloat() * this.getWidth() * 2.0F) - (double) this.getWidth(), d2, d0, d1);
            }
        }
    }

    private boolean isTickingEffects = false;
    private List<Map.Entry<Either<EffectInstance, Effect>, EntityPotionEffectEvent.Cause>> effectsToProcess = Lists.newArrayList();

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    protected void updatePotionEffects() {
        this.isTickingEffects = true;
        Iterator<Effect> iterator = this.activePotionsMap.keySet().iterator();

        try {
            while (iterator.hasNext()) {
                Effect effect = iterator.next();
                EffectInstance effectinstance = this.activePotionsMap.get(effect);
                if (!effectinstance.tick((LivingEntity) (Object) this)) {
                    if (!this.world.isRemote && !MinecraftForge.EVENT_BUS.post(new PotionEvent.PotionExpiryEvent((LivingEntity) (Object) this, effectinstance))) {

                        EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effectinstance, null, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.EXPIRATION);
                        if (event.isCancelled()) {
                            continue;
                        }

                        iterator.remove();
                        this.onFinishedPotionEffect(effectinstance);
                    }
                } else if (effectinstance.getDuration() % 600 == 0) {
                    this.onChangedPotionEffect(effectinstance, false);
                }
            }
        } catch (ConcurrentModificationException ignored) {
        }

        isTickingEffects = false;
        for (Map.Entry<Either<EffectInstance, Effect>, EntityPotionEffectEvent.Cause> e : effectsToProcess) {
            Either<EffectInstance, Effect> either = e.getKey();
            EntityPotionEffectEvent.Cause cause = e.getValue();
            bridge$pushEffectCause(cause);
            if (either.left().isPresent()) {
                addPotionEffect(either.left().get());
            } else {
                removePotionEffect(either.right().get());
            }
        }
        effectsToProcess.clear();

        if (this.potionsNeedUpdate) {
            if (!this.world.isRemote) {
                this.updatePotionMetadata();
            }

            this.potionsNeedUpdate = false;
        }

        int i = this.dataManager.get(POTION_EFFECTS);
        boolean flag1 = this.dataManager.get(HIDE_PARTICLES);
        if (i > 0) {
            boolean flag;
            if (this.isInvisible()) {
                flag = this.rand.nextInt(15) == 0;
            } else {
                flag = this.rand.nextBoolean();
            }

            if (flag1) {
                flag &= this.rand.nextInt(5) == 0;
            }

            if (flag && i > 0) {
                double d0 = (double) (i >> 16 & 255) / 255.0D;
                double d1 = (double) (i >> 8 & 255) / 255.0D;
                double d2 = (double) (i >> 0 & 255) / 255.0D;
                this.world.addParticle(flag1 ? ParticleTypes.AMBIENT_ENTITY_EFFECT : ParticleTypes.ENTITY_EFFECT, this.posX + (this.rand.nextDouble() - 0.5D) * (double) this.getWidth(), this.posY + this.rand.nextDouble() * (double) this.getHeight(), this.posZ + (this.rand.nextDouble() - 0.5D) * (double) this.getWidth(), d0, d1, d2);
            }
        }
    }

    /**
     * @author IzzelAliz
     * @reason
     */
    @Overwrite
    public boolean addPotionEffect(EffectInstance effectInstanceIn) {
        EntityPotionEffectEvent.Cause cause = bridge$getEffectCause().orElse(EntityPotionEffectEvent.Cause.UNKNOWN);
        if (isTickingEffects) {
            effectsToProcess.add(Maps.immutableEntry(Either.left(effectInstanceIn), cause));
            return true;
        }

        if (!this.isPotionApplicable(effectInstanceIn)) {
            return false;
        } else {
            EffectInstance effectinstance = this.activePotionsMap.get(effectInstanceIn.getPotion());

            boolean override = false;
            if (effectinstance != null) {
                override = new EffectInstance(effectinstance).combine(effectInstanceIn);
            }

            EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effectinstance, effectInstanceIn, cause, override);
            if (event.isCancelled()) {
                return false;
            }

            MinecraftForge.EVENT_BUS.post(new PotionEvent.PotionAddedEvent((LivingEntity) (Object) this, effectinstance, effectInstanceIn));
            if (effectinstance == null) {
                this.activePotionsMap.put(effectInstanceIn.getPotion(), effectInstanceIn);
                this.onNewPotionEffect(effectInstanceIn);
                return true;
            } else if (event.isOverride()) {
                effectinstance.combine(effectInstanceIn);
                this.onChangedPotionEffect(effectinstance, true);
                return true;
            } else {
                return false;
            }
        }
    }

    @SuppressWarnings("unused") // mock
    public EffectInstance c(@Nullable Effect potioneffectin, EntityPotionEffectEvent.Cause cause) {
        bridge$pushEffectCause(cause);
        return removeActivePotionEffect(potioneffectin);
    }

    @Inject(method = "removeActivePotionEffect", cancellable = true, at = @At("HEAD"))
    public void arclight$clearActive(Effect effect, CallbackInfoReturnable<EffectInstance> cir) {
        EntityPotionEffectEvent.Cause cause = bridge$getEffectCause().orElse(EntityPotionEffectEvent.Cause.UNKNOWN);
        if (isTickingEffects) {
            effectsToProcess.add(Maps.immutableEntry(Either.right(effect), cause));
            cir.setReturnValue(null);
            return;
        }

        EffectInstance effectInstance = this.activePotionsMap.get(effect);
        if (effectInstance == null) {
            cir.setReturnValue(null);
            return;
        }

        EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent((LivingEntity) (Object) this, effectInstance, null, cause);
        if (event.isCancelled()) {
            cir.setReturnValue(null);
        }
    }

    private transient boolean arclight$fallSuccess;

    @Inject(method = "fall", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER, target = "Lnet/minecraft/entity/LivingEntity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    public void arclight$fall(float distance, float damageMultiplier, CallbackInfo ci) {
        if (!arclight$fallSuccess) {
            ci.cancel();
        }
    }

    @Redirect(method = "fall", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    public boolean arclight$fall(LivingEntity livingEntity, DamageSource source, float amount) {
        return arclight$fallSuccess = livingEntity.attackEntityFrom(source, amount);
    }

    @Override
    public void bridge$dropExperience() {
    }

    @Override
    public void bridge$createWitherRose(LivingEntity entity) {
        if (!this.world.isRemote && entity instanceof WitherEntity) {
            boolean flag3 = false;
            if (net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(this.world, (ServerPlayerEntity) (Object) this)) {
                BlockPos blockposition = new BlockPos(this.posX, this.posY, this.posZ);
                BlockState iblockdata = Blocks.WITHER_ROSE.getDefaultState();
                if (this.world.getBlockState(blockposition).isAir() && iblockdata.isValidPosition(this.world, blockposition)) {
                    this.world.setBlockState(blockposition, iblockdata, 3);
                    flag3 = true;
                }
            }
            if (!flag3) {
                ItemEntity entityitem = new ItemEntity(this.world, this.posX, this.posY, this.posZ, new ItemStack(Items.WITHER_ROSE));
                this.world.addEntity(entityitem);
            }
        }
    }
}
