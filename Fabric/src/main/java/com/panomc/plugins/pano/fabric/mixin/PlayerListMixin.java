package com.panomc.plugins.pano.fabric.mixin;

import com.panomc.plugins.pano.fabric.FabricPreLoginHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void pano_placeNewPlayer(
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie clientData,
            CallbackInfo ci
    ) {
        Component disconnectReason = FabricPreLoginHandler.INSTANCE.handlePreLogin(player);
        if (disconnectReason != null) {
            connection.send(new ClientboundDisconnectPacket(disconnectReason));
            connection.disconnect(disconnectReason);
            ci.cancel();
        }
    }
}
