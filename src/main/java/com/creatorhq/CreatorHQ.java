package com.creatorhq;

import org.bukkit.*;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CreatorHQ extends JavaPlugin implements TabExecutor, Listener {

    record P(String name, long exp, String reason, String by) {
        boolean active() { return exp == 0 || exp > System.currentTimeMillis(); }
    }

    static class Store {
        final CreatorHQ pl; final File file; YamlConfiguration cfg; boolean dirty;
        Store(CreatorHQ pl, String name) {
            this.pl = pl; file = new File(pl.getDataFolder(), name);
            cfg = YamlConfiguration.loadConfiguration(file);
        }
        void reload() { cfg = YamlConfiguration.loadConfiguration(file); }
        synchronized void touch() {
            if (dirty || !pl.isEnabled()) return;
            dirty = true;
            Bukkit.getScheduler().runTaskLater(pl, () -> flush(true), 40L);
        }
        void flush(boolean async) {
            dirty = false;
            String s = cfg.saveToString();
            Runnable w = () -> {
                try { file.getParentFile().mkdirs(); Files.writeString(file.toPath(), s); }
                catch (IOException e) { pl.getLogger().warning("Could not save " + file.getName()); }
            };
            if (async && pl.isEnabled()) Bukkit.getScheduler().runTaskAsynchronously(pl, w); else w.run();
        }
    }

    static final Map<String, String> PERM = new LinkedHashMap<>();
    static {
        String[][] t = {
            {"creatorhq.advancement", "advancement", "advancementall"},
            {"creatorhq.messages", "joinmessages", "leavemessages", "deathmessages", "feedback"},
            {"creatorhq.keepinventory", "keepinventory"},
            {"creatorhq.player", "invulnerable", "invulnerableall", "immortal", "feed", "heal"},
            {"creatorhq.nickname", "nickname"},
            {"creatorhq.mute", "mute", "unmute", "muteall", "unmuteall", "cleartimers"},
            {"creatorhq.ban", "ban", "unban"},
            {"creatorhq.freeze", "freeze", "unfreeze", "freezeall", "unfreezeall"},
            {"creatorhq.jail", "jail", "unjail"},
            {"creatorhq.vanish", "vanish"},
            {"creatorhq.unvanish", "unvanish"},
            {"creatorhq.inventory", "clearitems", "removeitem"},
            {"creatorhq.invrollback", "invrollback", "invrestore"},
            {"creatorhq.teleport", "tp", "tplook"},
            {"creatorhq.kit", "kit"},
            {"creatorhq.give", "give"},
            {"creatorhq.admin", "reload"}
        };
        for (String[] r : t) for (int i = 1; i < r.length; i++) PERM.put(r[i], r[0]);
    }
    static final Map<String, String> GK = Map.of(
        "joinmessages", "joinMessages", "leavemessages", "leaveMessages", "deathmessages", "deathMessages",
        "feedback", "feedback", "advancementall", "advancementAll",
        "muteall", "muteAll", "freezeall", "freezeAll", "invulnerableall", "invulnerableAll");
    static final Map<String, Boolean> GD = Map.of(
        "advancementAll", true, "joinMessages", true, "leaveMessages", true, "deathMessages", true,
        "keepInventory", false, "feedback", true, "muteAll", false, "freezeAll", false, "invulnerableAll", false);
    static final List<String> BOOL = List.of("true", "false");
    static final List<String> DURS = List.of("10s", "10m", "1h", "1d", "7d", "permanent");

    Store data, pun, kits, msgs, snaps;
    final Map<String, Boolean> G = new ConcurrentHashMap<>();
    final Set<UUID> advHidden = ConcurrentHashMap.newKeySet();
    final Set<UUID> invuln = ConcurrentHashMap.newKeySet();
    final Set<UUID> immortal = ConcurrentHashMap.newKeySet();
    final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    final Map<UUID, Boolean> kiPlayer = new ConcurrentHashMap<>();
    final Map<UUID, String> nicks = new ConcurrentHashMap<>();
    final Map<UUID, String> frozen = new ConcurrentHashMap<>();
    final Map<UUID, Location> jailed = new ConcurrentHashMap<>();
    final Map<UUID, P> mutes = new ConcurrentHashMap<>();
    final Map<UUID, P> bans = new ConcurrentHashMap<>();
    final Map<UUID, UUID> tags = new HashMap<>();
    Location jl;
    long lastKit;
    List<String> materials;

    // ---------------------------------------------------------------- lifecycle

    @Override public void onEnable() {
        saveDefaultConfig();
        data = new Store(this, "data.yml");
        pun = new Store(this, "punishments.yml");
        kits = new Store(this, "kits.yml");
        msgs = new Store(this, "messages.yml");
        snaps = new Store(this, "snapshots.yml");
        loadState();
        loadJail();
        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = getCommand("creator");
        if (cmd != null) { cmd.setExecutor(this); cmd.setTabCompleter(this); }
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) if (nicks.containsKey(p.getUniqueId())) refreshTag(p);
        }, 40L, 40L);
        if (G.containsKey("feedback")) applyFeedback();
        for (Player p : Bukkit.getOnlinePlayers()) setup(p);
        getLogger().info("Creator HQ Recording enabled.");
    }

    @Override public void onDisable() {
        for (UUID id : new ArrayList<>(tags.values())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        for (Player p : Bukkit.getOnlinePlayers()) setTagHidden(p, false);
        for (Store s : List.of(data, pun, kits, msgs, snaps)) s.flush(false);
    }

    void loadState() {
        var gs = data.cfg.getConfigurationSection("global");
        if (gs != null) for (String k : gs.getKeys(false)) G.put(k, gs.getBoolean(k));
        var ps = data.cfg.getConfigurationSection("players");
        boolean persist = getConfig().getBoolean("freeze.persist", true);
        boolean vpersist = getConfig().getBoolean("vanish.persist", true);
        if (ps != null) for (String k : ps.getKeys(false)) {
            UUID id;
            try { id = UUID.fromString(k); } catch (Exception e) { continue; }
            var s = ps.getConfigurationSection(k);
            if (s == null) continue;
            if (s.getBoolean("advHidden")) advHidden.add(id);
            if (s.getBoolean("invulnerable")) invuln.add(id);
            if (s.getBoolean("immortal")) immortal.add(id);
            if (vpersist && s.getBoolean("vanished")) vanished.add(id);
            if (s.contains("keepInv")) kiPlayer.put(id, s.getBoolean("keepInv"));
            if (s.getString("nick") != null) nicks.put(id, s.getString("nick"));
            if (persist && s.getString("frozen") != null) frozen.put(id, s.getString("frozen"));
            if (s.get("jail") instanceof Location l) jailed.put(id, l);
        }
        loadPun(mutes, "mutes");
        loadPun(bans, "bans");
    }

    void loadPun(Map<UUID, P> m, String type) {
        var sec = pun.cfg.getConfigurationSection(type);
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                var s = sec.getConfigurationSection(k);
                if (s == null) continue;
                m.put(UUID.fromString(k), new P(s.getString("name", "?"), s.getLong("exp"),
                        s.getString("reason", ""), s.getString("by", "")));
            } catch (Exception ignored) { }
        }
    }

    void loadJail() { jl = getConfig().get("jail.location") instanceof Location l ? l : null; }

    // ---------------------------------------------------------------- helpers

    void sp(UUID id, String key, Object v) { data.cfg.set("players." + id + "." + key, v); data.touch(); }
    void sg(String k, boolean v) { G.put(k, v); data.cfg.set("global." + k, v); data.touch(); }
    boolean g(String k) { return G.getOrDefault(k, GD.get(k)); }
    void flag(Set<UUID> set, String key, UUID id, boolean on) {
        if (on) set.add(id); else set.remove(id);
        sp(id, key, on ? true : null);
    }
    void savePun(String type, UUID id, P p) {
        String path = type + "." + id;
        if (p == null) pun.cfg.set(path, null);
        else {
            pun.cfg.set(path + ".name", p.name()); pun.cfg.set(path + ".exp", p.exp());
            pun.cfg.set(path + ".reason", p.reason()); pun.cfg.set(path + ".by", p.by());
        }
        pun.touch();
    }

    String raw(String k, String d) {
        String v = msgs.cfg.getString(k);
        if (v == null) { msgs.cfg.set(k, d); msgs.touch(); v = d; }
        return v;
    }
    String t(String k, String d, Object... a) {
        String v = raw(k, d);
        for (int i = 0; i < a.length; i++) v = v.replace("{" + i + "}", String.valueOf(a[i]));
        return ChatColor.translateAlternateColorCodes('&', raw("prefix", "&8[&bCreator HQ&8] &7") + v);
    }
    void say(CommandSender s, String k, String d, Object... a) { s.sendMessage(t(k, d, a)); }
    void usage(CommandSender s, String x) { say(s, "usage", "&cUsage: /creator {0}", x); }
    void bad(CommandSender s) { say(s, "invalid", "&cInvalid arguments."); }

    Boolean bool(String x) {
        return switch (x.toLowerCase()) {
            case "true", "on" -> Boolean.TRUE;
            case "false", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    long parseDur(String x) {
        x = x.toLowerCase();
        if (x.equals("permanent") || x.equals("perm")) return 0;
        Matcher m = Pattern.compile("(\\d{1,9})([smhdw])").matcher(x);
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        if (n <= 0) return -1;
        long mul = switch (m.group(2).charAt(0)) {
            case 's' -> 1000L; case 'm' -> 60000L; case 'h' -> 3600000L; case 'd' -> 86400000L; default -> 604800000L;
        };
        return n * mul;
    }
    static String human(long ms) {
        if (ms <= 0) return "permanent";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        long h = m / 60;
        return h < 48 ? h + "h" : (h / 24) + "d";
    }

    OfflinePlayer off(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        OfflinePlayer o = null;
        try { o = Bukkit.getOfflinePlayerIfCached(name); } catch (Throwable ignored) { }
        if (o == null) {
            OfflinePlayer x = Bukkit.getOfflinePlayer(name);
            if (x.hasPlayedBefore()) o = x;
        }
        return o;
    }
    Player online(CommandSender s, String x) {
        Player p = Bukkit.getPlayerExact(x);
        if (p == null) say(s, "not-found", "&cPlayer not found or not online: {0}", x);
        return p;
    }
    List<Player> sel(CommandSender s, String x) {
        if (x.equalsIgnoreCase("@a")) return new ArrayList<>(Bukkit.getOnlinePlayers());
        Player p = online(s, x);
        return p == null ? null : List.of(p);
    }
    String label(List<Player> l) { return l.size() == 1 ? l.get(0).getName() : "all players"; }
    UUID find(Map<UUID, P> m, String name) {
        for (var e : m.entrySet()) if (e.getValue().name().equalsIgnoreCase(name)) return e.getKey();
        OfflinePlayer o = off(name);
        return o != null && m.containsKey(o.getUniqueId()) ? o.getUniqueId() : null;
    }
    String nameOf(OfflinePlayer o, String fb) { return o.getName() != null ? o.getName() : fb; }

    void notifyStaff(String msg) {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("creatorhq.notify")) p.sendMessage(msg);
        getLogger().info(ChatColor.stripColor(msg));
    }

    void audit(String line) {
        if (!getConfig().getBoolean("audit.enabled", true)) return;
        getLogger().info("[audit] " + line);
        String l = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + line + "\n";
        getDataFolder().mkdirs();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.writeString(new File(getDataFolder(), "audit.log").toPath(), l,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        });
    }

    int giveItems(Player p, List<ItemStack> items) {
        int left = 0;
        boolean drop = getConfig().getBoolean("give.drop-leftovers", true);
        for (ItemStack it : items) {
            Map<Integer, ItemStack> r = p.getInventory().addItem(it.clone());
            for (ItemStack x : r.values()) {
                left += x.getAmount();
                if (drop) p.getWorld().dropItemNaturally(p.getLocation(), x);
            }
        }
        return left;
    }

    List<ItemStack> kitItems(String name) {
        List<ItemStack> l = new ArrayList<>();
        List<?> raw = kits.cfg.getList("kits." + name);
        if (raw != null) for (Object o : raw) if (o instanceof ItemStack i) l.add(i);
        return l;
    }
    List<String> kitNames() {
        var sec = kits.cfg.getConfigurationSection("kits");
        return sec == null ? new ArrayList<>() : new ArrayList<>(sec.getKeys(false));
    }

    Material defMat() {
        String d = getConfig().getString("removeitem.default-material", "");
        return d == null || d.isBlank() ? null : Material.matchMaterial(d);
    }

    void applyFeedback() {
        boolean b = g("feedback");
        for (World w : Bukkit.getWorlds()) w.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, b);
    }

    // ---------------------------------------------------------------- inventory snapshots

    static boolean emp(ItemStack i) { return i == null || i.getType().isAir(); }
    static boolean same(ItemStack a, ItemStack b) { return (emp(a) && emp(b)) || (!emp(a) && !emp(b) && a.equals(b)); }
    static ItemStack[] cl(ItemStack[] a) {
        ItemStack[] r = new ItemStack[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i] == null ? null : a[i].clone();
        return r;
    }
    boolean invEmpty(PlayerInventory inv) {
        for (ItemStack i : inv.getStorageContents()) if (!emp(i)) return false;
        for (ItemStack i : inv.getArmorContents()) if (!emp(i)) return false;
        for (ItemStack i : inv.getExtraContents()) if (!emp(i)) return false;
        return true;
    }
    boolean invMatches(PlayerInventory inv, ItemStack[][] d) {
        ItemStack[] st = inv.getStorageContents(), ar = inv.getArmorContents(), ex = inv.getExtraContents();
        for (int i = 0; i < 36; i++) if (!same(i < st.length ? st[i] : null, d[0][i])) return false;
        for (int i = 0; i < 4; i++) if (!same(i < ar.length ? ar[i] : null, d[1][i])) return false;
        if (!same(ex.length > 0 ? ex[0] : null, d[2][0])) return false;
        return true;
    }

    List<String> snapIds(UUID u) {
        List<String> l = new ArrayList<>();
        var sec = snaps.cfg.getConfigurationSection("snapshots." + u);
        if (sec != null) for (String k : sec.getKeys(false)) if (k.matches("t\\d+")) l.add(k);
        l.sort((x, y) -> Long.compare(Long.parseLong(y.substring(1)), Long.parseLong(x.substring(1))));
        return l;
    }

    void prune(UUID u) {
        int max = Math.max(1, getConfig().getInt("invrollback.max-snapshots", 10));
        List<String> ids = snapIds(u);
        for (int i = max; i < ids.size(); i++) snaps.cfg.set("snapshots." + u + "." + ids.get(i), null);
        snaps.touch();
    }

    boolean snapFrom(UUID u, String name, String op, String by, ItemStack[] st, ItemStack[] ar, ItemStack[] ex, boolean prune) {
        if (!getConfig().getBoolean("invrollback.enabled", true)) return false;
        long time = System.currentTimeMillis();
        while (snaps.cfg.contains("snapshots." + u + ".t" + time)) time++;
        String base = "snapshots." + u + ".t" + time;
        int count = 0;
        for (int i = 0; i < st.length; i++) if (!emp(st[i])) { snaps.cfg.set(base + ".slots.s" + i, st[i].clone()); count++; }
        for (int i = 0; i < ar.length; i++) if (!emp(ar[i])) { snaps.cfg.set(base + ".slots.a" + i, ar[i].clone()); count++; }
        for (int i = 0; i < ex.length; i++) if (!emp(ex[i])) { snaps.cfg.set(base + ".slots.o" + i, ex[i].clone()); count++; }
        if (count == 0) return false;
        snaps.cfg.set(base + ".uuid", u.toString());
        snaps.cfg.set(base + ".name", name);
        snaps.cfg.set(base + ".time", time);
        snaps.cfg.set(base + ".op", op);
        snaps.cfg.set(base + ".by", by);
        snaps.touch();
        if (prune) prune(u);
        return true;
    }
    boolean snap(Player p, String op, String by) {
        PlayerInventory inv = p.getInventory();
        return snapFrom(p.getUniqueId(), p.getName(), op, by, inv.getStorageContents(), inv.getArmorContents(), inv.getExtraContents(), true);
    }

    /** Returns {storage[36], armor[4], extra[1]} or null if the snapshot is missing, foreign or corrupt. */
    ItemStack[][] readSnap(UUID u, String id) {
        String base = "snapshots." + u + "." + id;
        if (!u.toString().equals(snaps.cfg.getString(base + ".uuid", ""))) return null;
        var sec = snaps.cfg.getConfigurationSection(base + ".slots");
        if (sec == null) return null;
        ItemStack[] st = new ItemStack[36], ar = new ItemStack[4], ex = new ItemStack[1];
        for (String k : sec.getKeys(false)) {
            if (!(sec.get(k) instanceof ItemStack it)) return null;
            try {
                int idx = Integer.parseInt(k.substring(1));
                switch (k.charAt(0)) {
                    case 's' -> { if (idx < 0 || idx >= 36) return null; st[idx] = it; }
                    case 'a' -> { if (idx < 0 || idx >= 4) return null; ar[idx] = it; }
                    case 'o' -> { if (idx != 0) return null; ex[0] = it; }
                    default -> { return null; }
                }
            } catch (Exception e) { return null; }
        }
        return new ItemStack[][]{st, ar, ex};
    }

    // ---------------------------------------------------------------- nickname

    void applyNick(Player p) {
        String n = nicks.get(p.getUniqueId());
        p.setDisplayName(n != null ? n : p.getName());
        p.setPlayerListName(n);
        refreshTag(p);
    }

    Entity tagEntity(UUID id) {
        UUID e = tags.get(id);
        return e == null ? null : Bukkit.getEntity(e);
    }

    void setTagHidden(Player p, boolean hide) {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        String tn = "chq" + p.getUniqueId().toString().replace("-", "").substring(0, 12);
        Team t = sb.getTeam(tn);
        if (hide) {
            if (t == null) t = sb.registerNewTeam(tn);
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            if (!t.hasEntry(p.getName())) t.addEntry(p.getName());
        } else if (t != null) {
            t.removeEntry(p.getName());
            t.unregister();
        }
    }

    void refreshTag(Player p) {
        UUID id = p.getUniqueId();
        String nick = nicks.get(id);
        Entity old = tagEntity(id);
        if (nick == null || !p.isOnline()) {
            if (old != null) old.remove();
            tags.remove(id);
            setTagHidden(p, false);
            return;
        }
        setTagHidden(p, true);
        if (old instanceof TextDisplay td && td.isValid() && p.getPassengers().contains(td)) {
            if (!nick.equals(td.getText())) td.setText(nick);
        } else {
            if (old != null) old.remove();
            TextDisplay td = p.getWorld().spawn(p.getLocation(), TextDisplay.class, d -> {
                d.setText(nick);
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setTransformation(new Transformation(new Vector3f(0f, 0.3f, 0f), new AxisAngle4f(),
                        new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
            });
            p.addPassenger(td);
            tags.put(id, td.getUniqueId());
        }
        if (vanished.contains(id)) applyVanish(p);
    }

    // ---------------------------------------------------------------- vanish / freeze

    /** Can this viewer see vanished players? Default: nobody (even OPs) unless vanish.staff-can-see is true. */
    boolean canSee(Player viewer) {
        return getConfig().getBoolean("vanish.staff-can-see", false)
                && viewer.hasPermission(getConfig().getString("vanish.see-permission", "creatorhq.vanish.see"));
    }

    void applyVanish(Player v) {
        boolean on = vanished.contains(v.getUniqueId());
        Entity td = tagEntity(v.getUniqueId());
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(v)) continue;
            boolean hide = on && !canSee(o);
            // hidePlayer/showPlayer are per-plugin: we only ever undo our own hide.
            if (hide) o.hidePlayer(this, v); else o.showPlayer(this, v);
            if (td != null) { if (hide) o.hideEntity(this, td); else o.showEntity(this, td); }
        }
    }

    boolean verifyVanish(Player v) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(v) || canSee(o)) continue;
            if (o.canSee(v)) return false;
        }
        return true;
    }

    List<String> vanishNames(boolean vanishedOnes) {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (vanished.contains(p.getUniqueId()) == vanishedOnes) l.add(p.getName());
        return l;
    }

    void freeze(Player p, String src) {
        frozen.put(p.getUniqueId(), src);
        if (getConfig().getBoolean("freeze.persist", true)) sp(p.getUniqueId(), "frozen", src);
        say(p, "frozen-you", "&bYou have been frozen.");
    }
    void unfreeze(UUID id) {
        frozen.remove(id);
        sp(id, "frozen", null);
        Player p = Bukkit.getPlayer(id);
        if (p != null) say(p, "unfrozen-you", "&aYou have been unfrozen.");
    }

    void setup(Player p) {
        if (!p.isOnline()) return;
        UUID id = p.getUniqueId();
        if (nicks.containsKey(id)) applyNick(p);
        if (g("freezeAll") && !frozen.containsKey(id) && !p.hasPermission("creatorhq.freeze.bypass"))
            frozen.put(id, "global");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || !vanished.contains(o.getUniqueId()) || canSee(p)) continue;
            p.hidePlayer(this, o);
            Entity td = tagEntity(o.getUniqueId());
            if (td != null) p.hideEntity(this, td);
        }
        if (vanished.contains(id)) applyVanish(p);
        if (jailed.containsKey(id) && jl != null) p.teleport(jl);
    }

    // ---------------------------------------------------------------- commands

    String norm(String x) {
        x = x.toLowerCase();
        return x.equals("remove-item") ? "removeitem" : x;
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 0) { say(s, "help", "Use /{0} <subcommand>. Press TAB to see all commands.", l); return true; }
        String sub = norm(a[0]);
        String need = PERM.get(sub);
        if (need == null) { say(s, "unknown", "&cUnknown subcommand: {0}", a[0]); return true; }
        if (sub.equals("give") && a.length > 1 && a[1].equalsIgnoreCase("kit")) need = "creatorhq.givekit";
        if (!s.hasPermission(need)) { say(s, "no-perm", "&cYou do not have permission to use this command."); return true; }
        audit(s.getName() + " /" + l + " " + String.join(" ", a));
        try { run(s, sub, a); }
        catch (Exception e) {
            say(s, "error", "&cAn error occurred: {0}", String.valueOf(e.getMessage()));
            getLogger().warning("Command error: " + e);
        }
        return true;
    }

    void run(CommandSender s, String sub, String[] a) {
        int n = a.length;
        switch (sub) {
            case "advancement" -> {
                if (n < 3 || bool(a[2]) == null) { usage(s, "advancement <player|@a> <true|false>"); return; }
                List<Player> ts = sel(s, a[1]);
                if (ts == null) return;
                boolean b = bool(a[2]);
                for (Player p : ts) flag(advHidden, "advHidden", p.getUniqueId(), !b);
                say(s, b ? "adv-on" : "adv-off", b ? "Advancement announcements enabled for {0}."
                        : "Advancement announcements disabled for {0}.", label(ts));
            }
            case "joinmessages", "leavemessages", "deathmessages", "feedback",
                 "advancementall", "muteall", "freezeall", "invulnerableall" -> {
                Boolean b = n < 2 ? null : bool(a[1]);
                if (b == null) { usage(s, sub + " <true|false>"); return; }
                sg(GK.get(sub), b);
                if (sub.equals("feedback")) applyFeedback();
                if (sub.equals("freezeall")) {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        UUID id = p.getUniqueId();
                        if (b && !frozen.containsKey(id) && !p.hasPermission("creatorhq.freeze.bypass")) freeze(p, "global");
                    }
                    if (!b) for (UUID id : new ArrayList<>(frozen.keySet()))
                        if ("global".equals(frozen.get(id))) unfreeze(id);
                }
                say(s, "global-set", "Global {0} is now {1}.", sub, b ? "ON (true)" : "OFF (false)");
                if (sub.equals("muteall"))
                    Bukkit.broadcastMessage(t(b ? "muteall-on" : "muteall-off", b ? "Chat has been muted." : "Chat has been unmuted."));
            }
            case "keepinventory" -> {
                if (n == 2 && bool(a[1]) != null) {
                    boolean b = bool(a[1]);
                    sg("keepInventory", b);
                    say(s, "ki-global", "Global Keep Inventory setting updated successfully ({0}).", b);
                    return;
                }
                if (n == 3) {
                    if (!s.hasPermission("creatorhq.keepinventory.player")) {
                        say(s, "no-perm", "&cYou do not have permission to use this command."); return;
                    }
                    OfflinePlayer o = off(a[1]);
                    if (o == null) { say(s, "not-found", "&cPlayer not found: {0}", a[1]); return; }
                    UUID id = o.getUniqueId();
                    String nm = nameOf(o, a[1]);
                    if (a[2].equalsIgnoreCase("reset")) {
                        kiPlayer.remove(id);
                        sp(id, "keepInv", null);
                        say(s, "ki-reset", "Individual Keep Inventory setting reset for {0}.", nm);
                    } else {
                        Boolean b = bool(a[2]);
                        if (b == null) { bad(s); return; }
                        kiPlayer.put(id, b);
                        sp(id, "keepInv", b);
                        say(s, b ? "ki-on" : "ki-off", b ? "Keep Inventory enabled for {0}." : "Keep Inventory disabled for {0}.", nm);
                    }
                    return;
                }
                usage(s, "keepinventory <true|false> | keepinventory <player> <true|false|reset>");
            }
            case "unmuteall" -> {
                sg("muteAll", false);
                boolean ind = n >= 2 && a[1].equalsIgnoreCase("players");
                if (ind) { mutes.clear(); pun.cfg.set("mutes", null); pun.touch(); }
                say(s, "unmuteall", "Global mute disabled{0}.", ind ? " and all individual mutes cleared" : "");
            }
            case "unfreezeall" -> {
                sg("freezeAll", false);
                for (UUID id : new ArrayList<>(frozen.keySet())) unfreeze(id);
                say(s, "unfreezeall", "All plugin-frozen players have been unfrozen.");
            }
            case "invulnerable", "immortal" -> {
                if (n < 3 || bool(a[2]) == null) { usage(s, sub + " <player> <true|false>"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                boolean b = bool(a[2]);
                flag(sub.equals("immortal") ? immortal : invuln, sub.equals("immortal") ? "immortal" : "invulnerable", p.getUniqueId(), b);
                say(s, "state-set", "{0} for {1} is now {2}.", sub, p.getName(), b);
            }
            case "feed", "heal" -> {
                Player p;
                if (n >= 2) { p = online(s, a[1]); if (p == null) return; }
                else if (s instanceof Player me) p = me;
                else { usage(s, sub + " <player>"); return; }
                if (sub.equals("feed")) { p.setFoodLevel(20); p.setSaturation(20f); p.setExhaustion(0f); }
                else p.setHealth(p.getMaxHealth());
                say(s, sub + "-done", sub.equals("feed") ? "{0} has been fed." : "{0} has been healed.", p.getName());
            }
            case "nickname" -> {
                if (n < 3) { usage(s, "nickname <player> <newNickname|reset>"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                UUID id = p.getUniqueId();
                String nick = String.join(" ", Arrays.copyOfRange(a, 2, n)).replace("§", "");
                if (nick.equalsIgnoreCase("reset")) {
                    nicks.remove(id); sp(id, "nick", null);
                    applyNick(p);
                    say(s, "nick-reset", "Nickname of {0} has been reset.", p.getName());
                } else {
                    if (getConfig().getBoolean("nickname.allow-colors", false)) nick = ChatColor.translateAlternateColorCodes('&', nick);
                    if (ChatColor.stripColor(nick).length() > getConfig().getInt("nickname.max-length", 32)) {
                        say(s, "nick-long", "&cNickname is too long."); return;
                    }
                    nicks.put(id, nick); sp(id, "nick", nick);
                    applyNick(p);
                    say(s, "nick-set", "Nickname updated to {0}.", nick);
                }
            }
            case "mute", "ban" -> {
                if (n < 3) { usage(s, sub + " <player> <duration> <reason...>"); return; }
                OfflinePlayer o = off(a[1]);
                if (o == null) { say(s, "not-found", "&cPlayer not found: {0}", a[1]); return; }
                long d = parseDur(a[2]);
                if (d < 0) { say(s, "bad-duration", "&cInvalid duration. Use 10s, 10m, 1h, 1d, 7d or permanent."); return; }
                if (d > getConfig().getLong("punish.max-duration-days", 365) * 86400000L) {
                    say(s, "long-duration", "&cDuration exceeds the configured maximum."); return;
                }
                String reason = n > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, n)) : "No reason given";
                P p = new P(nameOf(o, a[1]), d == 0 ? 0 : System.currentTimeMillis() + d, reason, s.getName());
                boolean mute = sub.equals("mute");
                (mute ? mutes : bans).put(o.getUniqueId(), p);
                savePun(mute ? "mutes" : "bans", o.getUniqueId(), p);
                Player on = o.getPlayer();
                if (on != null) {
                    if (mute) say(on, "muted-you", "&cYou have been muted for {0}. Reason: {1}", human(d), reason);
                    else on.kickPlayer(banScreen(p));
                }
                notifyStaff(t(mute ? "muted" : "banned", mute ? "{0} has been muted for {1} by {2}. Reason: {3}"
                        : "{0} has been banned for {1} by {2}. Reason: {3}", p.name(), human(d), s.getName(), reason));
            }
            case "unmute", "unban" -> {
                if (n < 2) { usage(s, sub + " <player>"); return; }
                boolean mute = sub.equals("unmute");
                Map<UUID, P> m = mute ? mutes : bans;
                UUID id = find(m, a[1]);
                if (id == null) { say(s, "not-punished", "&c{0} is not {1}.", a[1], mute ? "muted" : "banned"); return; }
                P p = m.remove(id);
                savePun(mute ? "mutes" : "bans", id, null);
                Player on = Bukkit.getPlayer(id);
                if (mute && on != null) say(on, "unmuted-you", "&aYou have been unmuted.");
                notifyStaff(t(mute ? "unmuted" : "unbanned", mute ? "{0} has been unmuted." : "{0} has been unbanned.", p.name()));
            }
            case "cleartimers" -> {
                if (n < 2) { usage(s, "cleartimers <player>"); return; }
                OfflinePlayer o = off(a[1]);
                P m = o == null ? null : mutes.get(o.getUniqueId());
                if (m == null || m.exp() == 0) { say(s, "no-timers", "&cNo plugin timers to clear for {0}.", a[1]); return; }
                mutes.remove(o.getUniqueId());
                savePun("mutes", o.getUniqueId(), null);
                say(s, "timers-cleared", "Temporary mute timer cleared for {0}.", m.name());
            }
            case "freeze" -> {
                if (n < 2) { usage(s, "freeze <player>"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                freeze(p, "individual");
                say(s, "frozen", "{0} has been frozen.", p.getName());
            }
            case "unfreeze" -> {
                if (n < 2) { usage(s, "unfreeze <player>"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                if (!frozen.containsKey(p.getUniqueId())) { say(s, "not-frozen", "&c{0} is not frozen by this plugin.", p.getName()); return; }
                unfreeze(p.getUniqueId());
                say(s, "unfrozen", "{0} has been unfrozen.", p.getName());
            }
            case "jail" -> {
                if (n < 2) { usage(s, "jail <player> | jail set"); return; }
                if (a[1].equalsIgnoreCase("set")) {
                    if (!(s instanceof Player me)) { say(s, "players-only", "&cOnly players can use this."); return; }
                    getConfig().set("jail.location", me.getLocation());
                    saveConfig(); loadJail();
                    say(s, "jail-set", "Jail location set to your position.");
                    return;
                }
                if (jl == null) { say(s, "jail-none", "&cNo jail set. Stand at the spot and run /creator jail set"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                UUID id = p.getUniqueId();
                if (jailed.containsKey(id)) { say(s, "jail-already", "&c{0} is already jailed.", p.getName()); return; }
                jailed.put(id, p.getLocation());
                sp(id, "jail", p.getLocation());
                p.teleport(jl);
                say(p, "jailed-you", "&cYou have been jailed.");
                say(s, "jailed", "{0} has been jailed.", p.getName());
            }
            case "unjail" -> {
                if (n < 2) { usage(s, "unjail <player>"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                Location back = jailed.get(p.getUniqueId());
                if (back == null) { say(s, "not-jailed", "&c{0} is not jailed.", p.getName()); return; }
                if (p.teleport(back)) {
                    jailed.remove(p.getUniqueId());
                    sp(p.getUniqueId(), "jail", null);
                    say(p, "unjailed-you", "&aYou have been released.");
                    say(s, "unjailed", "{0} has been released.", p.getName());
                } else say(s, "tp-failed", "&cTeleport failed, player stays jailed.");
            }
            case "vanish", "unvanish" -> {
                boolean want = sub.equals("vanish");
                Player p;
                if (n >= 2) { p = online(s, a[1]); if (p == null) return; }
                else if (s instanceof Player me) p = me;
                else { usage(s, sub + " <player>"); return; }
                UUID id = p.getUniqueId();
                if (want == vanished.contains(id)) {
                    if (want) say(s, "already-vanished", "&cPlayer {0} is already vanished.", p.getName());
                    else say(s, "not-vanished", "&cPlayer {0} is not currently vanished.", p.getName());
                    return;
                }
                flag(vanished, "vanished", id, want);
                applyVanish(p);
                if (want && !verifyVanish(p)) {
                    flag(vanished, "vanished", id, false);
                    applyVanish(p);
                    say(s, "vanish-failed", "&cVanish failed: some players could still see {0}. No changes were kept.", p.getName());
                    return;
                }
                if (want) say(s, "vanish-on", "Player {0} is now vanished.", p.getName());
                else say(s, "vanish-off", "Player {0} is now visible.", p.getName());
                if (!s.equals(p)) say(p, want ? "vanish-you-on" : "vanish-you-off",
                        want ? "You are now vanished." : "You are now visible.");
            }
            case "clearitems" -> {
                if (n < 2) { usage(s, "clearitems <player|@a>"); return; }
                List<Player> ts = sel(s, a[1]);
                if (ts == null) return;
                int made = 0;
                for (Player p : ts) {
                    if (snap(p, "clearitems", s.getName())) made++;
                    PlayerInventory inv = p.getInventory();
                    inv.setStorageContents(new ItemStack[inv.getStorageContents().length]);
                    if (getConfig().getBoolean("clearitems.include-armor", true)) inv.setArmorContents(new ItemStack[4]);
                    if (getConfig().getBoolean("clearitems.include-offhand", true)) inv.setItemInOffHand(new ItemStack(Material.AIR));
                }
                say(s, "cleared", "Inventory cleared for {0}. Snapshots saved: {1}.", label(ts), made);
            }
            case "removeitem" -> {
                List<Player> ts;
                Material m;
                if (n == 1) { ts = new ArrayList<>(Bukkit.getOnlinePlayers()); m = defMat(); }
                else {
                    ts = sel(s, a[1]);
                    if (ts == null) return;
                    m = n >= 3 ? Material.matchMaterial(a[2]) : defMat();
                }
                if (m == null) {
                    say(s, "no-material", "&cNo valid material. Set removeitem.default-material in config.yml or use /creator removeitem <player|@a> <material>");
                    return;
                }
                int total = 0, made = 0;
                for (Player p : ts) {
                    int c = 0;
                    for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == m) c += it.getAmount();
                    if (c > 0 && snap(p, "removeitem", s.getName())) made++;
                    p.getInventory().remove(m);
                    total += c;
                }
                say(s, "removed", "Removed {0} x{1} from {2}. Snapshots saved: {3}.", m.name(), total, label(ts), made);
            }
            case "invrollback", "invrestore" -> {
                if (n < 2) { usage(s, "invrollback <player> [list|latest|<number>] [confirm]"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                UUID id = p.getUniqueId();
                List<String> ids = snapIds(id);
                if (n >= 3 && a[2].equalsIgnoreCase("list")) {
                    if (!s.hasPermission("creatorhq.invrollback.list")) {
                        say(s, "no-perm", "&cYou do not have permission to use this command."); return;
                    }
                    if (ids.isEmpty()) { say(s, "no-snap", "&cNo valid inventory snapshot exists for {0}.", p.getName()); return; }
                    say(s, "snap-list", "Snapshots for {0} (1 = newest):", p.getName());
                    for (int i = 0; i < ids.size(); i++) {
                        String base = "snapshots." + id + "." + ids.get(i);
                        s.sendMessage(ChatColor.GRAY + "" + (i + 1) + ". " + new Date(snaps.cfg.getLong(base + ".time"))
                                + " - " + snaps.cfg.getString(base + ".op", "?") + " by " + snaps.cfg.getString(base + ".by", "?"));
                    }
                    return;
                }
                if (ids.isEmpty()) { say(s, "no-snap", "&cNo valid inventory snapshot exists for {0}.", p.getName()); return; }
                int idx = 0;
                boolean confirm = false;
                for (int i = 2; i < n; i++) {
                    if (a[i].equalsIgnoreCase("confirm")) confirm = true;
                    else if (a[i].equalsIgnoreCase("latest")) idx = 0;
                    else {
                        try { idx = Integer.parseInt(a[i]) - 1; }
                        catch (NumberFormatException e) { bad(s); return; }
                    }
                }
                if (idx < 0 || idx >= ids.size()) {
                    say(s, "snap-index", "&cNo snapshot with that number. Use /creator invrollback {0} list", p.getName()); return;
                }
                ItemStack[][] sd = readSnap(id, ids.get(idx));
                if (sd == null) {
                    say(s, "rollback-failed", "&cInventory rollback failed. No changes were applied.");
                    audit("INVROLLBACK FAILED (unreadable snapshot) by " + s.getName() + " target=" + p.getName());
                    return;
                }
                PlayerInventory inv = p.getInventory();
                boolean curEmpty = invEmpty(inv);
                if (!curEmpty && getConfig().getBoolean("invrollback.require-confirm", true) && !confirm) {
                    say(s, "rollback-confirm", "&e{0} has items now. Rollback will REPLACE them (a backup snapshot is saved first). Run again: /creator invrollback {0} {1} confirm", p.getName(), idx + 1);
                    return;
                }
                ItemStack[] bs = cl(inv.getStorageContents()), ba = cl(inv.getArmorContents()), be = cl(inv.getExtraContents());
                if (!curEmpty) snapFrom(id, p.getName(), "pre-rollback", s.getName(), bs, ba, be, false);
                inv.setStorageContents(cl(sd[0]));
                inv.setArmorContents(cl(sd[1]));
                inv.setExtraContents(cl(sd[2]));
                if (!invMatches(inv, sd)) {
                    inv.setStorageContents(bs); inv.setArmorContents(ba); inv.setExtraContents(be);
                    say(s, "rollback-failed", "&cInventory rollback failed. No changes were applied.");
                    audit("INVROLLBACK FAILED (verification) by " + s.getName() + " target=" + p.getName());
                    return;
                }
                prune(id);
                say(s, "rollback-ok", "Inventory rollback completed successfully for {0}.", p.getName());
                say(p, "rollback-you", "&aYour inventory has been restored by staff.");
                audit("INVROLLBACK by " + s.getName() + " target=" + p.getName() + " snapshot=" + (idx + 1));
            }
            case "tp" -> {
                if (n == 2) {
                    if (!(s instanceof Player me)) { say(s, "players-only", "&cOnly players can use this. Use /creator tp <p1> <p2>"); return; }
                    Player t = online(s, a[1]);
                    if (t == null) return;
                    me.teleport(t.getLocation());
                    say(s, "tp-done", "Teleported {0} to {1}.", me.getName(), t.getName());
                } else if (n >= 3) {
                    Player p1 = online(s, a[1]);
                    if (p1 == null) return;
                    Player p2 = online(s, a[2]);
                    if (p2 == null) return;
                    p1.teleport(p2.getLocation());
                    say(s, "tp-done", "Teleported {0} to {1}.", p1.getName(), p2.getName());
                } else usage(s, "tp <player> [player2]");
            }
            case "tplook" -> {
                if (!(s instanceof Player me) || n < 2) { usage(s, "tplook <player>  (player only)"); return; }
                Player t = online(s, a[1]);
                if (t == null) return;
                me.teleport(t.getLocation());
                say(s, "tplook-done", "Teleported to {0} with the same position and view direction.", t.getName());
            }
            case "kit" -> kitCmd(s, a);
            case "give" -> {
                if (n >= 3 && a[1].equalsIgnoreCase("kit")) { giveKit(s, a[2]); return; }
                if (n < 3) { usage(s, "give <player> <material> [amount] | give kit <sourcePlayer>"); return; }
                Player p = online(s, a[1]);
                if (p == null) return;
                Material m = Material.matchMaterial(a[2]);
                if (m == null || !m.isItem() || m.isAir()) { say(s, "bad-material", "&cUnknown item: {0}", a[2]); return; }
                int amt = 1;
                if (n >= 4) {
                    try { amt = Integer.parseInt(a[3]); } catch (NumberFormatException e) { bad(s); return; }
                }
                if (amt < 1 || amt > getConfig().getInt("give.max-amount", 2304)) { say(s, "bad-amount", "&cInvalid amount."); return; }
                int left = giveItems(p, List.of(new ItemStack(m, amt)));
                say(s, "given", "Gave {0} x{1} to {2}.{3}", m.name(), amt, p.getName(), left > 0 ? " (inventory full, leftovers handled)" : "");
            }
            case "reload" -> {
                reloadConfig();
                msgs.reload(); kits.reload();
                loadJail();
                for (Player p : Bukkit.getOnlinePlayers()) if (vanished.contains(p.getUniqueId())) applyVanish(p);
                List<String> w = new ArrayList<>();
                String dm = getConfig().getString("removeitem.default-material", "");
                if (dm != null && !dm.isBlank() && Material.matchMaterial(dm) == null) w.add("removeitem.default-material is invalid: " + dm);
                if (getConfig().get("jail.location") != null && jl == null) w.add("jail.location is invalid");
                if (getConfig().getInt("punish.max-duration-days", 365) < 1) w.add("punish.max-duration-days must be at least 1");
                if (getConfig().getInt("invrollback.max-snapshots", 10) < 1) w.add("invrollback.max-snapshots must be at least 1");
                say(s, "reloaded", "Configuration reloaded.");
                for (String x : w) s.sendMessage(ChatColor.RED + "Config warning: " + x);
            }
            default -> bad(s);
        }
    }

    void kitCmd(CommandSender s, String[] a) {
        int n = a.length;
        if (n < 2) { usage(s, "kit save|give|delete|list ... | kit <player> <kitName>"); return; }
        String k = a[1].toLowerCase();
        if (k.equals("list")) {
            List<String> ks = kitNames();
            say(s, "kit-list", "Kits: {0}", ks.isEmpty() ? "(none)" : String.join(", ", ks));
        } else if (k.equals("save")) {
            if (!(s instanceof Player me) || n < 3) { usage(s, "kit save <name> [overwrite]  (player only)"); return; }
            String name = a[2].toLowerCase();
            if (!name.matches("[a-z0-9_-]{1,32}")) { say(s, "kit-name", "&cKit name: letters, numbers, _ and - only (max 32)."); return; }
            if (kits.cfg.contains("kits." + name) && !(n >= 4 && a[3].equalsIgnoreCase("overwrite"))) {
                say(s, "kit-exists", "&cKit already exists. Add 'overwrite' at the end to replace it."); return;
            }
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack it : me.getInventory().getStorageContents()) if (it != null && !it.getType().isAir()) items.add(it.clone());
            if (items.isEmpty()) { say(s, "kit-empty", "&cYour inventory is empty."); return; }
            kits.cfg.set("kits." + name, items);
            kits.touch();
            say(s, "kit-saved", "Kit {0} saved ({1} stacks).", name, items.size());
        } else if (k.equals("delete")) {
            if (n < 3) { usage(s, "kit delete <name>"); return; }
            String name = a[2].toLowerCase();
            if (!kits.cfg.contains("kits." + name)) { say(s, "kit-none", "&cNo such kit: {0}", name); return; }
            kits.cfg.set("kits." + name, null);
            kits.touch();
            say(s, "kit-deleted", "Kit {0} deleted.", name);
        } else {
            String tgt, name;
            if (k.equals("give")) {
                if (n < 4) { usage(s, "kit give <player|@a> <kitName>"); return; }
                tgt = a[2]; name = a[3];
            } else {
                if (n < 3) { usage(s, "kit <player> <kitName>"); return; }
                tgt = a[1]; name = a[2];
            }
            List<ItemStack> items = kitItems(name.toLowerCase());
            if (items.isEmpty()) { say(s, "kit-none", "&cNo such kit: {0}", name); return; }
            List<Player> ts = sel(s, tgt);
            if (ts == null) return;
            for (Player p : ts) giveItems(p, items);
            say(s, "kit-given", "Kit {0} given to {1}.", name, label(ts));
        }
    }

    void giveKit(CommandSender s, String srcName) {
        Player src = Bukkit.getPlayerExact(srcName);
        if (src == null) { say(s, "not-found", "&cPlayer not found or not online: {0}", srcName); return; }
        ItemStack hand = src.getInventory().getItemInMainHand();
        if (!Tag.SHULKER_BOXES.isTagged(hand.getType()) || !(hand.getItemMeta() instanceof BlockStateMeta bsm)
                || !(bsm.getBlockState() instanceof ShulkerBox box)) {
            say(s, "kit-source", "&cThe source player must hold a filled shulker box in their main hand.");
            return;
        }
        List<ItemStack> kit = new ArrayList<>();
        for (ItemStack it : box.getInventory().getContents()) if (it != null && !it.getType().isAir()) kit.add(it.clone());
        if (kit.isEmpty()) { say(s, "kit-empty-box", "&cThe shulker box is empty."); return; }
        long now = System.currentTimeMillis();
        if (now - lastKit < getConfig().getLong("givekit.cooldown-seconds", 3) * 1000L) {
            say(s, "kit-cooldown", "&cPlease wait a moment before distributing again."); return;
        }
        lastKit = now;
        boolean inc = getConfig().getBoolean("givekit.include-source", true);
        int players = 0, left = 0;
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            if (!inc && p.equals(src)) continue;
            left += giveItems(p, kit);
            players++;
        }
        audit("GIVEKIT by " + s.getName() + " source=" + src.getName() + " players=" + players + " stacks=" + kit.size() + " leftover=" + left);
        say(s, "kit-done", "Recording kit distributed successfully to {0} player(s).", players);
        if (left > 0) say(s, "kit-left", "{0} item(s) did not fit in some inventories (handled per give.drop-leftovers).", left);
    }

    String banScreen(P p) {
        String m = getConfig().getString("ban.screen-message", "You are banned");
        if (getConfig().getBoolean("ban.show-reason", false)) m += "\n" + p.reason();
        return m;
    }

    // ---------------------------------------------------------------- tab completion

    List<String> players(boolean at) {
        List<String> l = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
        if (at) l.add("@a");
        return l;
    }
    List<String> mats() {
        if (materials == null) {
            materials = Arrays.stream(Material.values())
                    .filter(m -> m.isItem() && !m.isAir() && !m.name().startsWith("LEGACY_"))
                    .map(Material::name).toList();
        }
        return materials;
    }
    List<String> flt(Collection<String> in, String cur) {
        List<String> o = new ArrayList<>();
        String c = cur.toLowerCase();
        for (String x : in) if (x.toLowerCase().startsWith(c)) { o.add(x); if (o.size() >= 200) break; }
        Collections.sort(o);
        return o;
    }
    List<String> names(Map<UUID, P> m) {
        List<String> l = new ArrayList<>();
        for (P p : m.values()) l.add(p.name());
        return l;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> o = new ArrayList<>();
        int i = a.length;
        if (i == 1) {
            for (String x : new TreeSet<>(PERM.keySet())) if (s.hasPermission(PERM.get(x))) o.add(x);
            return flt(o, a[0]);
        }
        String sub = norm(a[0]);
        String need = PERM.get(sub);
        if (need == null || !s.hasPermission(need)) return o;
        String cur = a[i - 1];
        switch (sub) {
            case "advancement" -> { if (i == 2) o = players(true); else if (i == 3) o.addAll(BOOL); }
            case "invulnerable", "immortal" -> { if (i == 2) o = players(false); else if (i == 3) o.addAll(BOOL); }
            case "joinmessages", "leavemessages", "deathmessages", "feedback",
                 "advancementall", "muteall", "freezeall", "invulnerableall" -> { if (i == 2) o.addAll(BOOL); }
            case "keepinventory" -> {
                if (i == 2) {
                    o.addAll(BOOL);
                    if (s.hasPermission("creatorhq.keepinventory.player")) o.addAll(players(false));
                } else if (i == 3 && s.hasPermission("creatorhq.keepinventory.player")) {
                    o.addAll(BOOL);
                    o.add("reset");
                }
            }
            case "unmuteall" -> { if (i == 2) o.add("players"); }
            case "feed", "heal", "freeze", "unfreeze", "unjail", "tplook", "cleartimers" -> { if (i == 2) o = players(false); }
            case "vanish" -> { if (i == 2) o = vanishNames(false); }
            case "unvanish" -> { if (i == 2) o = vanishNames(true); }
            case "invrollback", "invrestore" -> {
                if (i == 2) o = players(false);
                else if (i == 3) {
                    o.addAll(List.of("latest", "confirm", "1", "2", "3"));
                    if (s.hasPermission("creatorhq.invrollback.list")) o.add("list");
                } else if (i == 4) o.add("confirm");
            }
            case "jail" -> { if (i == 2) { o = players(false); o.add("set"); } }
            case "nickname" -> { if (i == 2) o = players(false); else if (i == 3) o.add("reset"); }
            case "mute", "ban" -> { if (i == 2) o = players(false); else if (i == 3) o.addAll(DURS); }
            case "unmute" -> { if (i == 2) o = names(mutes); }
            case "unban" -> { if (i == 2) o = names(bans); }
            case "clearitems" -> { if (i == 2) o = players(true); }
            case "removeitem" -> { if (i == 2) o = players(true); else if (i == 3) o.addAll(mats()); }
            case "tp" -> { if (i == 2 || i == 3) o = players(false); }
            case "kit" -> {
                if (i == 2) { o.addAll(List.of("save", "give", "delete", "list")); o.addAll(players(false)); }
                else {
                    String k = a[1].toLowerCase();
                    if (i == 3) {
                        if (k.equals("give")) o.addAll(players(true));
                        else if (!k.equals("save") && !k.equals("list")) o.addAll(kitNames());
                    } else if (i == 4) {
                        if (k.equals("give")) o.addAll(kitNames());
                        else if (k.equals("save")) o.add("overwrite");
                    }
                }
            }
            case "give" -> {
                if (i == 2) { o.add("kit"); o.addAll(players(false)); }
                else if (i == 3) { if (a[1].equalsIgnoreCase("kit")) o = players(false); else o.addAll(mats()); }
                else if (i == 4 && !a[1].equalsIgnoreCase("kit")) o.addAll(List.of("1", "16", "64"));
            }
            default -> { }
        }
        return flt(o, cur);
    }

    // ---------------------------------------------------------------- events

    @EventHandler public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        P b = bans.get(e.getUniqueId());
        if (b == null) return;
        if (b.active()) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(b));
        else Bukkit.getScheduler().runTask(this, () -> { bans.remove(e.getUniqueId()); savePun("bans", e.getUniqueId(), null); });
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        boolean hide = vanished.contains(p.getUniqueId()) && getConfig().getBoolean("vanish.suppress-join-quit", true);
        if (!g("joinMessages") || hide) e.setJoinMessage(null);
        Bukkit.getScheduler().runTask(this, () -> setup(p));
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boolean hide = vanished.contains(id) && getConfig().getBoolean("vanish.suppress-join-quit", true);
        if (!g("leaveMessages") || hide) e.setQuitMessage(null);
        if (!getConfig().getBoolean("vanish.persist", true) && vanished.remove(id)) sp(id, "vanished", null);
        Entity td = tagEntity(id);
        if (td != null) td.remove();
        tags.remove(id);
        setTagHidden(p, false);
        if (!getConfig().getBoolean("freeze.persist", true)) frozen.remove(id);
    }

    @EventHandler public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        P m = mutes.get(id);
        if (m != null) {
            if (m.active()) {
                e.setCancelled(true);
                p.sendMessage(t("muted-chat", "&cYou are muted ({0} left). Reason: {1}",
                        m.exp() == 0 ? "permanent" : human(m.exp() - System.currentTimeMillis()), m.reason()));
                return;
            }
            Bukkit.getScheduler().runTask(this, () -> { mutes.remove(id); savePun("mutes", id, null); });
        }
        if (g("muteAll") && !p.hasPermission("creatorhq.mute.bypass")) {
            e.setCancelled(true);
            p.sendMessage(t("muteall-chat", "&cChat is currently muted."));
            return;
        }
        if (nicks.containsKey(id)) {
            String f = getConfig().getString("nickname.chat-format", "{name}: {message}");
            e.setFormat(f.replace("%", "%%").replace("{name}", "%1$s").replace("{message}", "%2$s"));
        }
    }

    /** Precedence: individual override > global setting > server default (left untouched). */
    @EventHandler(priority = EventPriority.HIGH) public void onDeath(PlayerDeathEvent e) {
        if (!g("deathMessages")) e.setDeathMessage(null);
        Boolean ov = kiPlayer.get(e.getEntity().getUniqueId());
        boolean keep = ov != null ? ov : g("keepInventory");
        if (keep) {
            e.setKeepInventory(true);
            e.getDrops().clear();
            if (getConfig().getBoolean("keepinventory.keep-level", true)) {
                e.setKeepLevel(true);
                e.setDroppedExp(0);
            }
        }
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        if (jl != null && jailed.containsKey(e.getPlayer().getUniqueId())) e.setRespawnLocation(jl);
    }

    @EventHandler public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!g("advancementAll") || advHidden.contains(e.getPlayer().getUniqueId())) {
            try { e.message(null); } catch (Throwable ignored) { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (invuln.contains(id) || g("invulnerableAll")) { e.setCancelled(true); return; }
        if (immortal.contains(id)) {
            double h = p.getHealth();
            if (h - e.getFinalDamage() < 1.0) {
                e.setDamage(0);
                if (h > 1.0) p.setHealth(1.0);
            }
        }
    }

    @EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent e) {
        if (frozen.isEmpty() && jailed.isEmpty()) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Location f = e.getFrom(), to = e.getTo();
        if (to == null) return;
        if (frozen.containsKey(id)) {
            if (f.getX() != to.getX() || f.getY() != to.getY() || f.getZ() != to.getZ()) {
                Location n = f.clone();
                n.setYaw(to.getYaw());
                n.setPitch(to.getPitch());
                e.setTo(n);
            }
            return;
        }
        if (jl != null && jailed.containsKey(id) && getConfig().getBoolean("jail.restrict-movement", true)) {
            if (f.getBlockX() == to.getBlockX() && f.getBlockY() == to.getBlockY() && f.getBlockZ() == to.getBlockZ()) return;
            double r = getConfig().getDouble("jail.radius", 5.0);
            if (to.getWorld() != jl.getWorld() || to.distanceSquared(jl) > r * r) e.setTo(jl);
        }
    }
    }
