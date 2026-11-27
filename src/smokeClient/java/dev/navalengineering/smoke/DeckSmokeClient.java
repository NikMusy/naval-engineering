package dev.navalengineering.smoke;

import dev.navalengineering.ship.ShipEntity;
import dev.navalengineering.simulation.DeckMotion;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class DeckSmokeClient implements ClientModInitializer {
    private boolean connected, sawShip, captured, advanced, observedBelow, observedBridge, releaseIssued, afterHelm;
    private int ticks, visible, aboard, throttleTicks, phase, phaseTicks, samples;
    private double maxFrameError, maxLocalLead, maxHorizontalLead, settledError;
    private String name;

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            connected = true;
            name = client.player.getName().getString();
            client.options.pauseOnLostFocus = false;
            client.options.hudHidden = true;
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (connected) report(client);
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient client) {
        if (!connected || client.player == null || client.world == null) return;
        ticks++;
        var own = client.player;
        if (own.getVehicle() instanceof ShipEntity ship) {
            aboard++;
            if (aboard <= 2) client.options.hudHidden = false;
            checkSamples(ship, own);
            if (name.equals("DeckPilot")) {
                pilot(client, ship);
            } else {
                walk(client, ship);
            }
        } else if (!captured && client.currentScreen == null) {
            var ships = client.world.getEntitiesByClass(ShipEntity.class,
                    new Box(own.getPos(), own.getPos()).expand(26), vessel -> vessel.blueprint() != null);
            String target = "DeckPilot".equals(name) ? "Northern Meridian" : "Squall";
            var subject = ships.stream().filter(s -> s.getName().getString().equals(target)).findFirst();
            if (subject.isPresent() && own.getY() > subject.get().getY() + 3) {
                var aim = subject.get().getPos().add(0, -1.1, 0).subtract(own.getEyePos());
                own.setYaw((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
                own.setPitch((float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z))));
                client.getToastManager().clear();
                visible++;
                sawShip = true;
                if (visible >= 22) {
                    captured = true;
                    String shot = name.equals("DeckPilot") ? "meridian.png" : "squall.png";
                    screenshot(client, shot);
                }
            }
        }
        if (ticks > 1800) report(client);
    }

    private void pilot(MinecraftClient client, ShipEntity ship) {
        int id = client.player.getId();
        if (throttleTicks > 210 && ship.isPilot(id) && !releaseIssued) {
            releaseIssued = true;
            KeyBinding.onKeyPressed(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_R));
        }
        if (releaseIssued) {
            client.options.rightKey.setPressed(false);
            var foot = ship.crewPosition(id);
            if (!ship.isPilot(id) && foot != null && foot.z() < 2.18) aimAt(client, ship, foot, -.8, 2.32);
            else {
                afterHelm |= foot != null && foot.z() > 1.95 && !ship.isPilot(id);
                idle(client);
            }
            return;
        }
        boolean atHelm = ship.canTakeHelm(client.player.getId());
        if (atHelm && !ship.isPilot(client.player.getId()) && aboard % 9 == 0) {
            KeyBinding.onKeyPressed(InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_R));
        }
        if (ship.isPilot(client.player.getId())) throttleTicks++;
        client.options.forwardKey.setPressed(throttleTicks > 0);
        client.options.rightKey.setPressed(throttleTicks > 27 && throttleTicks < 147);
    }

    private void walk(MinecraftClient client, ShipEntity ship) {
        var state = ship.crewPosition(client.player.getId());
        if (state == null) return;
        // Aim with the camera and press the actual key binding; the production client sends the packets.
        switch (phase) {
            case 0 -> {
                if (state.y() < -4.38 && state.z() >= 9.15) next();
                else aimAt(client, ship, state, 0, 9.65);
            }
            case 1 -> {
                idle(client);
                client.options.hudHidden = true;
                client.getToastManager().clear();
                client.player.setYaw(180 + ship.deckYaw());
                client.player.setPitch(4);
                if (phaseTicks == 8) screenshot(client, "machinery.png");
                if (phaseTicks > 11) {
                    client.options.hudHidden = false;
                    next();
                }
            }
            case 2 -> {
                if (state.y() > -1.7 && state.z() < 4.65) next();
                else aimAt(client, ship, state, 0, 4.3);
            }
            case 3 -> {
                if (state.y() > -.65 && state.z() < 2.42) next();
                else aimAt(client, ship, state, 0, 2.15);
            }
            case 4 -> {
                if (phaseTicks > 35) next();
                else aimAt(client, ship, state, 3.2, 2.28);
            }
            case 5 -> {
                if (Math.abs(state.x()) < .12) next();
                else aimAt(client, ship, state, 0, 2.26);
            }
            case 6 -> {
                if (state.y() < -1.3 && state.z() > 4.15) next();
                else aimAt(client, ship, state, 0, 4.55);
            }
            case 7 -> {
                if (state.y() > -.65 && state.z() < 2.48) next();
                else aimAt(client, ship, state, 0, 2.15);
            }
            default -> {
                advanced = true;
                idle(client);
            }
        }
        phaseTicks++;
        if (aboard % 40 == 0) System.out.println("DECK_SMOKE_WALK phase=" + phase + " state=" + state);
    }

    private void next() { phase++; phaseTicks = 0; }

    private static void idle(MinecraftClient client) { client.options.forwardKey.setPressed(false); }

    private static void screenshot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name, client.getFramebuffer(),
                message -> System.out.println("DECK_SMOKE_SCREENSHOT " + message.getString()));
    }

    private static void aimAt(MinecraftClient client, ShipEntity ship, DeckMotion.State at, double x, double z) {
        double yaw = Math.toDegrees(Math.atan2(at.x() - x, z - at.z()));
        client.player.setYaw((float) yaw + ship.deckYaw());
        client.player.setPitch(7);
        client.options.forwardKey.setPressed(true);
    }

    private void checkSamples(ShipEntity ship, Entity local) {
        for (Entity rider : ship.getPassengerList()) {
            var state = ship.crewPosition(rider.getId());
            if (state == null || aboard < 5) continue;
            maxFrameError = Math.max(maxFrameError, rider.getPos().distanceTo(ship.worldFeet(state)));
            if (rider.getId() == local.getId()) {
                var wire = ship.authoritativeCrewPosition(rider.getId());
                if (wire != null) {
                    double error = Math.sqrt(wire.distanceSquared(state));
                    maxLocalLead = Math.max(maxLocalLead, error);
                    maxHorizontalLead = Math.max(maxHorizontalLead, Math.hypot(wire.x() - state.x(), wire.z() - state.z()));
                    if (phase == 8 && phaseTicks > 16) settledError = Math.max(settledError, error);
                }
            }
            if ("DeckWalker".equals(rider.getName().getString())) {
                observedBelow |= state.y() < -4.35 && state.z() > 8.4;
                observedBridge |= state.y() > -.55 && state.z() < 2.8;
            }
            samples++;
        }
    }

    private void report(MinecraftClient client) {
        connected = false;
        boolean ok = samples > 225 && observedBelow && observedBridge && maxFrameError < .08
                && maxHorizontalLead < .83 && maxLocalLead < 1.25 && settledError < .03
                && (name.equals("DeckPilot") ? throttleTicks > 150 && afterHelm : advanced);
        String result = String.format(Locale.ROOT,
                "%s client=%s samples=%d seenHull=%s screenshot=%s seenBelow=%s seenBridge=%s " +
                        "throttleTicks=%d walkingRoute=%d releasedAndWalked=%s maxFrameError=%.6f_m " +
                        "maxPredictionLead=%.4f_m horizontalLead=%.4f_m settledError=%.6f_m",
                ok ? "PASS" : "FAIL", name, samples, sawShip, captured, observedBelow, observedBridge,
                throttleTicks, phase, afterHelm, maxFrameError, maxLocalLead, maxHorizontalLead, settledError);
        System.out.println("DECK_SMOKE_CLIENT " + result);
        try {
            Files.writeString(client.runDirectory.toPath().resolve("deck-result.txt"), result + System.lineSeparator());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            client.scheduleStop();
        }
    }
}
