package com.fongmi.android.tv.api.config;

import com.fongmi.android.tv.Setting;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.LiveParser;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class LiveConfig extends BaseConfig {

    private static final String TAG = LiveConfig.class.getSimpleName();

    private Live home;
    private List<Live> lives;
    private List<Rule> rules;
    private List<String> ads;

    private static class Loader {
        static volatile LiveConfig INSTANCE = new LiveConfig();
    }

    public static LiveConfig get() {
        return Loader.INSTANCE;
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static String getResp() {
        return get().getHome().getCore().getResp();
    }

    public static int getHomeIndex() {
        return get().getLives().indexOf(get().getHome());
    }

    public static boolean isOnly() {
        return get().getLives().size() == 1;
    }

    public static boolean isEmpty() {
        return get().getHome().isEmpty();
    }

    public static boolean hasUrl() {
        String url = getUrl();
        return url != null && !url.isEmpty();
    }

    public static void load(Config config, Callback callback) {
        get().clear().config(config).load(callback);
    }

    public LiveConfig init() {
        return config(Config.live());
    }

    public LiveConfig config(Config config) {
        this.config = config;
        if (config.isEmpty()) return this;
        this.sync = config.getUrl().equals(VodConfig.getUrl());
        return this;
    }

    public LiveConfig clear() {
        ads = null;
        home = null;
        lives = null;
        rules = null;
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.live();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.live();
    }

    @Override
    protected void load(Config config) throws Throwable {
        String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), TAG);
        if (Json.isObj(json)) checkJson(config, Json.parse(json).getAsJsonObject());
        else parseText(config, json);
    }

    public void load() {
        if (sync) return;
        load(new Callback());
    }

    private void parseText(Config config, String text) {
        Live live = new Live(UrlUtil.getName(config.getUrl()), config.getUrl()).sync();
        lives = new ArrayList<>(List.of(live));
        LiveParser.text(live, text);
        setHome(config, live, false);
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, LIVE));
        load(this.config = configs.get(0));
        Config.delete(config.getUrl());
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
    }

    public void parse(JsonObject object) {
        parseConfig(getConfig(), object);
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(object.getAsJsonArray("headers")));
        setProxy(Proxy.arrayFrom(object.getAsJsonArray("proxy")));
        setRules(Rule.arrayFrom(object.getAsJsonArray("rules")));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        String spider = Json.safeString(object, "spider");
        BaseLoader.get().parseJar(spider, false);
        setLives(Json.safeListElement(object, "lives").stream().map(e -> Live.objectFrom(e, spider)).distinct().collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Live> items = Live.findAll().stream().collect(Collectors.toMap(Live::getName, Function.identity()));
        getLives().forEach(live -> live.sync(items.get(live.getName())));
        setHome(config, getLives().isEmpty() ? new Live() : getLives().stream().filter(item -> item.getName().equals(config.getHome())).findFirst().orElse(getLives().get(0)), false);
    }

    public void setKeep(Channel channel) {
        if (home != null && !channel.getGroup().isHidden()) home.keep(channel).save();
    }

    public void applyKeepsToGroups(List<Group> items) {
        Set<String> key = Keep.getLive().stream().map(Keep::getKey).collect(Collectors.toSet());
        items.stream().filter(group -> !group.isKeep())
                .flatMap(group -> group.getChannel().stream())
                .filter(channel -> key.contains(channel.getName()))
                .forEach(channel -> items.get(0).add(channel));
    }

    public int[] findKeepPosition(List<Group> items) {
        String[] splits = getHome().getKeep().split(AppDatabase.SYMBOL);
        if (splits.length < 3) return new int[]{1, 0};
        for (int i = 0; i < items.size(); i++) {
            Group group = items.get(i);
            if (group.getName().equals(splits[0])) {
                int j = group.find(splits[1]);
                if (j != -1) {
                    group.getChannel().get(j).setIndex(splits[2]);
                    return new int[]{i, j};
                }
            }
        }
        return new int[]{1, 0};
    }

    public int[] findByChannelNumber(String number, List<Group> items) {
        for (int i = 0; i < items.size(); i++) {
            int j = items.get(i).find(Integer.parseInt(number));
            if (j != -1) return new int[]{i, j};
        }
        return new int[]{-1, -1};
    }

    public List<Live> getLives() {
        return lives == null ? lives = new ArrayList<>() : lives;
    }

    private void setLives(List<Live> lives) {
        this.lives = lives;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    private void setHeaders(List<Header> headers) {
        OkHttp.responseInterceptor().addAll(headers);
    }

    private void setProxy(List<Proxy> proxy) {
        OkHttp.authenticator().addAll(proxy);
        OkHttp.selector().addAll(proxy);
    }

    private void setHosts(List<String> hosts) {
        OkHttp.dns().addAll(hosts);
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
    }

    public Live getHome() {
        return home == null ? new Live() : home;
    }

    public Live getLive(String key) {
        return getLives().stream().filter(item -> item.getName().equals(key)).findFirst().orElse(new Live());
    }

    public void setHome(Live home) {
        setHome(getConfig(), home, true);
    }

    private void setHome(Config config, Live live, boolean save) {
        home = live;
        home.setActivated(true);
        config.setHome(home.getName());
        if (save) config.save();
        getLives().forEach(item -> item.setActivated(home));
        if (!save && (home.isBoot() || Setting.isBootLive())) ConfigEvent.boot();
    }
}
