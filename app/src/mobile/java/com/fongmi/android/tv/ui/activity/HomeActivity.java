package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.ServiceFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.MobileWindow;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.WebHomeChromeStartup;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements WebHomeChromeController.Host {

    public static final String EXTRA_NAV_POSITION = "nav_position";
    private static final String STATE_CURRENT_POSITION = "currentPosition";

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private WebHomeChromeController mChrome;
    private Config mStartupConfig;
    private boolean wideWindow;
    private int currentPosition;
    private int selectedTab = R.id.vod;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        wideWindow = MobileWindow.isWide(this);
        currentPosition = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_CURRENT_POSITION, 0);
        mStartupConfig = Config.vod();
        mChrome = new WebHomeChromeController(this, mBinding, this, savedInstanceState, WebHomeChromeStartup.restore(mStartupConfig));
        mBinding.getRoot().addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> checkWindowShape(right - left, bottom - top));
        bindNavigation();
        PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this));
        initFragment(savedInstanceState);
        initConfig();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putInt(STATE_CURRENT_POSITION, currentPosition);
        if (mChrome != null) mChrome.save(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (intent.hasExtra(EXTRA_NAV_POSITION)) {
            change(intent.getIntExtra(EXTRA_NAV_POSITION, 0));
            intent.removeExtra(EXTRA_NAV_POSITION);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 4 -> SettingDanmakuFragment.newInstance();
            case 5 -> ServiceFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
        else restorePosition(currentPosition);
    }

    private void restorePosition(int position) {
        setNavigation();
        syncNavigationSelection();
        changeFragment(position <= 0 ? 0 : position);
    }

    private void initConfig() {
        VodConfig.get().config(mStartupConfig == null ? Config.vod() : mStartupConfig).load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                resetVodChrome();
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private void setNavigation() {
        setTabVisible(R.id.vod, true);
        setTabVisible(R.id.setting, true);
        setTabVisible(R.id.live, LiveConfig.hasUrl());
        setTabVisible(R.id.service, true);
        syncNavigationSelection();
    }

    private void bindNavigation() {
        for (int id : new int[]{R.id.vod, R.id.live, R.id.service, R.id.setting}) {
            View item = mBinding.navigation.findViewById(id);
            if (item != null) item.setOnClickListener(v -> onTabSelected(id));
        }
        applyTabSelection(selectedTab);
    }

    private void setTabVisible(int id, boolean visible) {
        View item = mBinding.navigation.findViewById(id);
        if (item != null) item.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        setNavigationVisible(true);
        if (position < 2) selectNavigation(position);
        else changeFragment(position);
    }

    public void setNavigationVisible(boolean visible) {
        mBinding.navigation.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.getRoot().requestApplyInsets();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    private void onTabSelected(int itemId) {
        setNavigationVisible(true);
        if (itemId == R.id.setting) changeFragment(1);
        else if (itemId == R.id.vod) changeFragment(0);
        else if (itemId == R.id.live) openLive();
        else if (itemId == R.id.service) changeFragment(5);
        applyTabSelection(itemId);
    }

    private void applyTabSelection(int itemId) {
        selectedTab = itemId;
        int[] items = {R.id.vod, R.id.live, R.id.service, R.id.setting};
        int[] pills = {R.id.vodPill, R.id.livePill, R.id.servicePill, R.id.settingPill};
        for (int i = 0; i < items.length; i++) {
            View item = mBinding.navigation.findViewById(items[i]);
            View pill = mBinding.navigation.findViewById(pills[i]);
            boolean selected = items[i] == itemId;
            if (item != null) item.setSelected(selected);
            if (pill != null) pill.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private int navigationId(int position) {
        if (position == 1) return R.id.setting;
        if (position == 5) return R.id.service;
        return R.id.vod;
    }

    private void selectNavigation(int position) {
        int itemId = navigationId(position);
        if (selectedTab == itemId) changeFragment(position);
        else onTabSelected(itemId);
    }

    private void syncNavigationSelection() {
        int itemId = navigationId(currentPosition);
        if (selectedTab == itemId) return;
        applyTabSelection(itemId);
    }

    private boolean changeFragment(int position) {
        boolean changed = mManager.change(position);
        if (changed) currentPosition = position;
        refreshWebHomeChromeLayout();
        return changed;
    }

    private void refreshWebHomeChromeLayout() {
        if (mChrome != null) mChrome.refreshLayout();
    }

    private void resetVodChrome() {
        if (mChrome != null) mChrome.setLegacyToolbar(true);
    }

    public void applyWebHomeDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.applyDefault(WebHomeChromeStartup.resolve(VodConfig.get().getConfig(), site));
    }

    public void setWebHomeChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (isStartupChrome(payload)) WebHomeChromeStartup.remember(VodConfig.get().getConfig(), VodConfig.get().getHome(), payload);
        if (mChrome != null) mChrome.setChrome(payload);
    }

    private boolean isStartupChrome(JsonObject payload) {
        try {
            return payload != null && payload.has("startup") && payload.get("startup").getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }

    public void restoreWebHomeChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.restore();
    }

    public void setWebHomeLegacyToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            if (mChrome != null) mChrome.setChrome(normalWebHomeChrome());
            return;
        }
        if (mChrome != null) mChrome.setLegacyToolbar(visible);
    }

    public void refreshWebHomeChromeState() {
        onWebHomeChromeChanged(getWebHomeChromeMode());
    }

    private JsonObject normalWebHomeChrome() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", "normal");
        return object;
    }

    public void openVod() {
        resetVodChrome();
        setNavigationVisible(true);
        onTabSelected(R.id.vod);
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.openVodHome();
    }

    public String getWebHomeChromeMode() {
        return mChrome == null ? "normal" : mChrome.getMode();
    }

    public WebHomeViewport getWebHomeViewport() {
        return mChrome == null ? WebHomeViewport.EMPTY : mChrome.getViewport();
    }

    @Override
    public boolean isWebHomeChromeActive() {
        return mManager != null && mManager.isVisible(0);
    }

    @Override
    public void onWebHomeChromeChanged(String mode) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeChrome(mode);
    }

    @Override
    public void onWebHomeViewportChanged(WebHomeViewport viewport) {
        if (mManager == null) return;
        VodFragment fragment = (VodFragment) mManager.getFragment(0);
        if (fragment != null) fragment.applyWebHomeViewport(viewport);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mChrome != null) mChrome.onConfigurationChanged();
        App.post(this::checkWindowShape, 100);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (mChrome != null) mChrome.onWindowFocusChanged(hasFocus);
    }

    private void checkWindowShape() {
        checkWindowShape(MobileWindow.getWidth(this), MobileWindow.getHeight(this));
    }

    private void checkWindowShape(int width, int height) {
        if (width <= 0 || height <= 0) return;
        boolean wide = width > height;
        if (wideWindow != wide) {
            wideWindow = wide;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (mChrome != null && mChrome.consumeBack()) {
            return;
        } else if (mBinding.navigation.findViewById(R.id.vod).getVisibility() != View.VISIBLE) {
            setNavigation();
        } else if (mManager.isVisible(2) || mManager.isVisible(4)) {
            change(1);
        } else if (mManager.isVisible(5)) {
            change(0);
        } else if (mManager.isVisible(1)) {
            change(0);
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) Util.moveToBackground(this);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        if (mChrome != null) mChrome.destroy();
        LiveConfig.get().clear();
        VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }
}
