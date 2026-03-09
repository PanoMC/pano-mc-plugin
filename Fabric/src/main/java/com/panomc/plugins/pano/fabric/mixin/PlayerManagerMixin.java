package com.panomc.plugins.pano.fabric.mixin;

import com.panomc.plugins.pano.fabric.FabricPreLoginHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    @Inject(method = "onPlayerConnect", at = @At("HEAD"), cancellable = true)
    private void pano_onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        Text disconnectReason = FabricPreLoginHandler.INSTANCE.handlePreLogin(player);
        if (disconnectReason != null) {
            connection.send(new DisconnectS2CPacket(disconnectReason));
            connection.disconnect(disconnectReason);
            ci.cancel();
        }
    }
}
