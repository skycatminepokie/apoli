package io.github.apace100.apoli.mixin;

import io.github.apace100.apoli.component.PowerHolderComponent;
import io.github.apace100.apoli.networking.ModPackets;
import io.github.apace100.apoli.power.*;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Nameable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin extends LivingEntity implements Nameable, CommandOutput {

    @Shadow
    public abstract boolean damage(DamageSource source, float amount);

    @Shadow
    public abstract EntityDimensions getDimensions(EntityPose pose);

    @Shadow
    public abstract ItemStack getEquippedStack(EquipmentSlot slot);

    @Shadow
    protected boolean isSubmergedInWater;

    @Shadow
    @Final
    public PlayerInventory inventory;

    @Shadow
    public abstract ItemEntity dropItem(ItemStack stack, boolean retainOwnership);

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    @ModifyArg(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Vec3d;add(DDD)Lnet/minecraft/util/math/Vec3d;"), index = 1)
    private double adjustVerticalSwimSpeed(double original) {
        return PowerHolderComponent.modify(this, ModifySwimSpeedPower.class, original);
    }

    @Inject(method = "dismountVehicle", at = @At("HEAD"))
    private void sendPlayerDismountPacket(CallbackInfo ci) {
        if(!world.isClient && getVehicle() instanceof PlayerEntity) {
            PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
            buf.writeInt(getId());
            ServerPlayNetworking.send((ServerPlayerEntity) getVehicle(), ModPackets.PLAYER_DISMOUNT, buf);
        }
    }

    @Inject(method = "updateSwimming", at = @At("TAIL"))
    private void updateSwimmingPower(CallbackInfo ci) {
        if(PowerHolderComponent.hasPower(this, SwimmingPower.class)) {
            this.setSwimming(this.isSprinting() && !this.hasVehicle());
            this.touchingWater = this.isSwimming();
            if (this.isSwimming()) {
                this.fallDistance = 0.0F;
                Vec3d look = this.getRotationVector();
                move(MovementType.SELF, new Vec3d(look.x/4, look.y/4, look.z/4));
            }
        } else if(PowerHolderComponent.hasPower(this, IgnoreWaterPower.class)) {
            this.setSwimming(false);
        }
    }

    @Inject(method = "wakeUp(ZZ)V", at = @At("HEAD"))
    private void invokeWakeUpAction(boolean bl, boolean updateSleepingPlayers, CallbackInfo ci) {
        if(!bl && !updateSleepingPlayers && getSleepingPosition().isPresent()) {
            BlockPos sleepingPos = getSleepingPosition().get();
            PowerHolderComponent.getPowers(this, ActionOnWakeUp.class).stream().filter(p -> p.doesApply(sleepingPos)).forEach(p -> p.executeActions(sleepingPos, Direction.DOWN));
        }
    }

    // Prevent healing if DisableRegenPower
    // Note that this function was called "shouldHeal" instead of "canFoodHeal" at some point in time.
    @Inject(method = "canFoodHeal", at = @At("HEAD"), cancellable = true)
    private void disableHeal(CallbackInfoReturnable<Boolean> info) {
        if(PowerHolderComponent.hasPower(this, DisableRegenPower.class)) {
            info.setReturnValue(false);
        }
    }

    // ModifyExhaustion
    @ModifyVariable(at = @At("HEAD"), method = "addExhaustion", ordinal = 0, name = "exhaustion")
    private float modifyExhaustion(float exhaustionIn) {
        return PowerHolderComponent.modify(this, ModifyExhaustionPower.class, exhaustionIn);
    }

    @Inject(method = "dropInventory", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/PlayerInventory;dropAll()V"))
    private void dropAdditionalInventory(CallbackInfo ci) {
        PowerHolderComponent.getPowers(this, InventoryPower.class).forEach(inventory -> {
            if(inventory.shouldDropOnDeath()) {
                for(int i = 0; i < inventory.size(); ++i) {
                    ItemStack itemStack = inventory.getStack(i);
                    if(inventory.shouldDropOnDeath(itemStack)) {
                        if (!itemStack.isEmpty() && EnchantmentHelper.hasVanishingCurse(itemStack)) {
                            inventory.removeStack(i);
                        } else {
                            ((PlayerEntity)(Object)this).dropItem(itemStack, true, false);
                            inventory.setStack(i, ItemStack.EMPTY);
                        }
                    }
                }
            }
        });
    }

    @Inject(method = "canEquip", at = @At("HEAD"), cancellable = true)
    private void preventArmorDispensing(ItemStack stack, CallbackInfoReturnable<Boolean> info) {
        EquipmentSlot slot = MobEntity.getPreferredEquipmentSlot(stack);
        PowerHolderComponent component = PowerHolderComponent.KEY.get(this);
        if(component.getPowers(RestrictArmorPower.class).stream().anyMatch(rap -> !rap.canEquip(stack, slot))) {
            info.setReturnValue(false);
        }
    }
}
