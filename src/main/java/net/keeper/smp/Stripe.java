package net.keeper.smp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Rank delivery by polling the Stripe API. Polling was chosen over webhooks so
 * the server needs no public URL, no open port and no TLS certificate, which
 * matters on a hosted panel.
 *
 * The key in config must be a RESTRICTED key with read access to Checkout
 * Sessions and Subscriptions. A secret key would let this code move money and
 * has no business being on a game server.
 */
public class Stripe {

    private record Pending(UUID uuid, String name, Rank rank, long expires) {
    }

    private final KeeperPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, Pending> codes = new HashMap<>();
    private final Set<String> handledSessions = new HashSet<>();
    private final Random random = new Random();
    private final File file;
    private boolean warned = false;

    public Stripe(KeeperPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stripe-state.yml");
        loadState();
    }

    public boolean enabled() {
        String key = key();
        return plugin.getConfig().getBoolean("stripe.enabled", false)
                && key != null && key.startsWith("rk_");
    }

    private String key() {
        return plugin.getConfig().getString("stripe.secret-key", "");
    }

    // ---------------- codes ----------------

    public String issueCode(Player player, Rank rank) {
        String code;
        do {
            code = "STX-" + randomChunk();
        } while (codes.containsKey(code));
        long ttl = plugin.getConfig().getInt("stripe.code-ttl-minutes", 30) * 60_000L;
        codes.put(code, new Pending(player.getUniqueId(), player.getName(), rank,
                System.currentTimeMillis() + ttl));
        saveState();
        return code;
    }

    private String randomChunk() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    private void expireCodes() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(e -> e.getValue().expires() < now);
    }

    // ---------------- polling ----------------

    /** Runs off the main thread. */
    public void poll() {
        if (!enabled()) {
            if (!warned && plugin.getConfig().getBoolean("stripe.enabled", false)) {
                warned = true;
                plugin.getLogger().warning("Stripe is enabled but the key is missing or is not a "
                        + "restricted key (rk_...). Rank delivery is off.");
            }
            return;
        }
        expireCodes();
        try {
            pollSessions();
            pollSubscriptions();
        } catch (Exception ex) {
            plugin.getLogger().warning("Stripe poll failed: " + ex.getMessage());
        }
    }

    private JsonObject get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + key())
                .header("Stripe-Version", "2024-06-20")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from Stripe: "
                    + trim(response.body()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String trim(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    private void pollSessions() throws IOException, InterruptedException {
        JsonObject root = get("https://api.stripe.com/v1/checkout/sessions"
                + "?limit=100&expand[]=data.subscription");
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) return;

        for (JsonElement element : data) {
            JsonObject session = element.getAsJsonObject();
            String id = str(session, "id");
            if (id == null || handledSessions.contains(id)) continue;
            if (!"paid".equals(str(session, "payment_status"))) continue;

            String reference = str(session, "client_reference_id");
            if (reference == null) continue;
            Pending pending = codes.get(reference.trim().toUpperCase());
            if (pending == null) {
                // Either already used, expired, or bought without a code.
                handledSessions.add(id);
                continue;
            }

            Rank rank = pending.rank();
            // Cross check the amount so a cheap link cannot claim a dear rank.
            int expected = plugin.getConfig().getInt("stripe.amounts." + rank.name(), -1);
            int paid = session.has("amount_total") && !session.get("amount_total").isJsonNull()
                    ? session.get("amount_total").getAsInt() : -1;
            if (expected > 0 && paid > 0 && paid < expected) {
                plugin.getLogger().warning("Session " + id + " paid " + paid
                        + " cents but " + rank.name() + " costs " + expected + ". Skipped.");
                handledSessions.add(id);
                continue;
            }

            long expiry = System.currentTimeMillis() + 31L * 24 * 3600 * 1000;
            String subscriptionId = null;
            JsonElement subElement = session.get("subscription");
            if (subElement != null && subElement.isJsonObject()) {
                JsonObject sub = subElement.getAsJsonObject();
                subscriptionId = str(sub, "id");
                if (sub.has("current_period_end") && !sub.get("current_period_end").isJsonNull()) {
                    expiry = sub.get("current_period_end").getAsLong() * 1000L;
                }
            } else if (subElement != null && subElement.isJsonPrimitive()) {
                subscriptionId = subElement.getAsString();
            }

            handledSessions.add(id);
            codes.remove(reference.trim().toUpperCase());
            final long finalExpiry = expiry;
            final String finalSub = subscriptionId;
            Bukkit.getScheduler().runTask(plugin, () -> {
                plugin.ranks().grant(pending.uuid(), rank, finalExpiry, finalSub);
                plugin.getLogger().info("Granted " + rank.name() + " to " + pending.name()
                        + " from Stripe session " + id);
                Bukkit.broadcast(Util.text("&6[Ranks] &f" + pending.name()
                        + " &7just picked up " + rank.display + "&7. Thank you."));

                Player buyer = Bukkit.getPlayer(pending.uuid());
                if (buyer != null) {
                    buyer.playSound(buyer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                    buyer.showTitle(Title.title(Util.text("&6Rank Delivered!"),
                            Util.text("&f" + rank.display + " &7is now yours")));
                }
            });
            saveState();
        }
        // Keep the handled set from growing without bound.
        if (handledSessions.size() > 500) {
            handledSessions.clear();
            saveState();
        }
    }

    /** Extend or drop ranks based on live subscription status. */
    private void pollSubscriptions() throws IOException, InterruptedException {
        JsonObject root = get("https://api.stripe.com/v1/subscriptions?limit=100&status=all");
        JsonArray data = root.getAsJsonArray("data");
        if (data == null) return;

        Map<String, JsonObject> byId = new HashMap<>();
        for (JsonElement element : data) {
            JsonObject sub = element.getAsJsonObject();
            String id = str(sub, "id");
            if (id != null) byId.put(id, sub);
        }

        for (Data.PlayerData player : plugin.data().loaded().values()) {
            if (player.subscriptionId == null) continue;
            JsonObject sub = byId.get(player.subscriptionId);
            if (sub == null) continue;
            String status = str(sub, "status");
            long periodEnd = sub.has("current_period_end") && !sub.get("current_period_end").isJsonNull()
                    ? sub.get("current_period_end").getAsLong() * 1000L : 0L;
            boolean live = "active".equals(status) || "trialing".equals(status);

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (live && periodEnd > 0) {
                    plugin.data().get(player.uuid).rankExpiry = periodEnd;
                } else if (!live && !"past_due".equals(status)) {
                    plugin.ranks().revoke(player.uuid);
                }
            });
        }
    }

    private String str(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()) return null;
        return object.get(field).getAsString();
    }

    // ---------------- state ----------------

    private void loadState() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        handledSessions.addAll(yml.getStringList("handled"));
        if (yml.isConfigurationSection("codes")) {
            for (String code : yml.getConfigurationSection("codes").getKeys(false)) {
                String path = "codes." + code + ".";
                try {
                    codes.put(code, new Pending(
                            UUID.fromString(yml.getString(path + "uuid")),
                            yml.getString(path + "name", "?"),
                            Rank.parse(yml.getString(path + "rank"), Rank.KEEPER_PLUS),
                            yml.getLong(path + "expires")));
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void saveState() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("handled", new java.util.ArrayList<>(handledSessions));
        for (Map.Entry<String, Pending> e : codes.entrySet()) {
            String path = "codes." + e.getKey() + ".";
            yml.set(path + "uuid", e.getValue().uuid().toString());
            yml.set(path + "name", e.getValue().name());
            yml.set(path + "rank", e.getValue().rank().name());
            yml.set(path + "expires", e.getValue().expires());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed saving stripe state: " + ex.getMessage());
        }
    }

    /** Quick connectivity check for /keeper stripe. */
    public String status() {
        if (!plugin.getConfig().getBoolean("stripe.enabled", false)) return "disabled in config";
        String key = key();
        if (key == null || key.isBlank() || key.contains("PASTE_YOUR")) return "no key set";
        if (key.startsWith("sk_")) return "refusing to use a full secret key, use a restricted key";
        if (!key.startsWith("rk_")) return "key is not a restricted key";
        try {
            get("https://api.stripe.com/v1/subscriptions?limit=1");
            return "connected, " + codes.size() + " code(s) waiting";
        } catch (Exception ex) {
            return "error: " + ex.getMessage();
        }
    }
}
