package com.panomc.plugins.pano.fabric.mixin;

import com.panomc.plugins.pano.fabric.FabricPreLoginHandler;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    // placeNewPlayer is always reached on the server main thread (platform-modules-2), so the
    // pre-login check can't block here waiting on the platform round trip. FabricPreLoginHandler
    // decides for itself whether to cancel `ci` and finish the login asynchronously.
    @Inject(method = "placeNewPlayer", at = @At("HEAD"), cancellable = true)
    private void pano_placeNewPlayer(
            Connection connection,
            ServerPlayer player,
            CommonListenerCookie clientData,
            CallbackInfo ci
    ) {
        FabricPreLoginHandler.INSTANCE.handlePreLogin(connection, player, clientData, ci);
    }
}
