package org.schabi.newpipe.fragments.detail;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.Fragment;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.ViewPager;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.DisplayMetrics;
import android.util.Log;

import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.SimpleImageLoadingListener;

import org.schabi.newpipe.R;
import org.schabi.newpipe.ReCaptchaActivity;
import org.schabi.newpipe.download.DownloadDialog;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.fragments.EmptyFragment;
import org.schabi.newpipe.fragments.list.comments.CommentsFragment;
import org.schabi.newpipe.fragments.list.videos.RelatedVideosFragment;
import org.schabi.newpipe.local.dialog.PlaylistAppendDialog;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.*;
import org.schabi.newpipe.player.event.PlayerEventListener;
import org.schabi.newpipe.player.event.PlayerServiceEventListener;
import org.schabi.newpipe.player.helper.PlayerHelper;
import org.schabi.newpipe.player.playqueue.*;
import org.schabi.newpipe.report.ErrorActivity;
import org.schabi.newpipe.report.UserAction;
import org.schabi.newpipe.settings.SettingsContentObserver;
import org.schabi.newpipe.util.*;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import icepick.State;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static org.schabi.newpipe.extractor.StreamingService.ServiceInfo.MediaCapability.COMMENTS;
import static org.schabi.newpipe.player.playqueue.PlayQueueItem.RECOVERY_UNSET;
import static org.schabi.newpipe.util.AnimationUtils.animateView;

public class VideoDetailFragment
        extends BaseStateFragment<StreamInfo>
        implements BackPressable,
        SharedPreferences.OnSharedPreferenceChangeListener,
        View.OnClickListener,
        View.OnLongClickListener,
        PlayerEventListener,
        PlayerServiceEventListener,
        SettingsContentObserver.OnChangeListener {
    public static final String AUTO_PLAY = "auto_play";

    private boolean isFragmentStopped;

    private int updateFlags = 0;
    private static final int RELATED_STREAMS_UPDATE_FLAG = 0x1;
    private static final int RESOLUTIONS_MENU_UPDATE_FLAG = 0x2;
    private static final int TOOLBAR_ITEMS_UPDATE_FLAG = 0x4;
    private static final int COMMENTS_UPDATE_FLAG = 0x8;
    private static final float MAX_OVERLAY_ALPHA = 0.9f;

    public static final String ACTION_SHOW_MAIN_PLAYER = "org.schabi.newpipe.fragments.VideoDetailFragment.ACTION_SHOW_MAIN_PLAYER";
    public static final String ACTION_HIDE_MAIN_PLAYER = "org.schabi.newpipe.fragments.VideoDetailFragment.ACTION_HIDE_MAIN_PLAYER";

    private boolean autoPlayEnabled;
    private boolean showRelatedStreams;
    private boolean showComments;
    private String selectedTabTag;

    @State
    protected int serviceId = Constants.NO_SERVICE_ID;
    @State
    protected String name;
    @State
    protected String url;
    @State
    protected PlayQueue playQueue;
    @State
    int bottomSheetState = BottomSheetBehavior.STATE_EXPANDED;

    private StreamInfo currentInfo;
    private Disposable currentWorker;
    @NonNull
    private CompositeDisposable disposables = new CompositeDisposable();
    @Nullable
    private Disposable positionSubscriber = null;

    private List<VideoStream> sortedVideoStreams;
    private int selectedVideoStreamIndex = -1;
    private BottomSheetBehavior bottomSheetBehavior;
    private BroadcastReceiver broadcastReceiver;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private Menu menu;

    private Spinner spinnerToolbar;

    private LinearLayout contentRootLayoutHiding;

    private View thumbnailBackgroundButton;
    private ImageView thumbnailImageView;
    private ImageView thumbnailPlayButton;
    private AnimatedProgressBar positionView;

    private View videoTitleRoot;
    private TextView videoTitleTextView;
    private ImageView videoTitleToggleArrow;
    private TextView videoCountView;

    private TextView detailControlsBackground;
    private TextView detailControlsPopup;
    private TextView detailControlsAddToPlaylist;
    private TextView detailControlsDownload;
    private TextView appendControlsDetail;
    private TextView detailDurationView;
    private TextView detailPositionView;

    private LinearLayout videoDescriptionRootLayout;
    private TextView videoUploadDateView;
    private TextView videoDescriptionView;

    private View uploaderRootLayout;
    private TextView uploaderTextView;
    private ImageView uploaderThumb;

    private TextView thumbsUpTextView;
    private ImageView thumbsUpImageView;
    private TextView thumbsDownTextView;
    private ImageView thumbsDownImageView;
    private TextView thumbsDisabledTextView;

    private RelativeLayout overlay;
    private LinearLayout overlayMetadata;
    private ImageView overlayThumbnailImageView;
    private TextView overlayTitleTextView;
    private TextView overlayChannelTextView;
    private LinearLayout overlayButtons;
    private ImageButton overlayPlayPauseButton;
    private ImageButton overlayCloseButton;

    private static final String COMMENTS_TAB_TAG = "COMMENTS";
    private static final String RELATED_TAB_TAG = "NEXT VIDEO";
    private static final String EMPTY_TAB_TAG = "EMPTY TAB";

    private AppBarLayout appBarLayout;
    private  ViewPager viewPager;
    private TabAdaptor pageAdapter;
    private TabLayout tabLayout;
    private FrameLayout relatedStreamsLayout;

    private SettingsContentObserver settingsContentObserver;
    private ServiceConnection serviceConnection;
    private boolean bounded;
    private MainPlayer playerService;
    private VideoPlayerImpl player;


    /*//////////////////////////////////////////////////////////////////////////
    // Service management
    //////////////////////////////////////////////////////////////////////////*/

    private ServiceConnection getServiceConnection(boolean playAfterConnect) {
        return new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (DEBUG) Log.d(TAG, "Player service is disconnected");

                unbind();
            }

            @Override
            public void onServiceConnected(ComponentName compName, IBinder service) {
                if (DEBUG) Log.d(TAG, "Player service is connected");
                MainPlayer.LocalBinder localBinder = (MainPlayer.LocalBinder) service;

                playerService = localBinder.getService();
                player = localBinder.getPlayer();

                startPlayerListener();

                // It will do nothing if the player is not in fullscreen mode
                hideSystemUIIfNeeded();

                if (!player.videoPlayerSelected()) return;

                if (currentInfo == null && !wasCleared()) selectAndLoadVideo(serviceId, url, name, playQueue);

                if (player.getPlayQueue() != null) addVideoPlayerView();

                // If the video is playing but orientation changed let's make the video in fullscreen again

                if (isLandscape()) checkLandscape();
                else if (player.isInFullscreen()) player.toggleFullscreen();

                if (currentInfo != null && isAutoplayEnabled() && player.getParentActivity() == null || playAfterConnect)
                    openVideoPlayer();
            }
        };
    }

    private void bind() {
        if (DEBUG) Log.d(TAG, "bind() called");

        Intent serviceIntent = new Intent(getContext(), MainPlayer.class);
        final boolean success = getContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (!success) getContext().unbindService(serviceConnection);
        bounded = success;
    }

    private void unbind() {
        if (DEBUG) Log.d(TAG, "unbind() called");

        if (bounded) {
            getContext().unbindService(serviceConnection);
            bounded = false;
            stopPlayerListener();
            playerService = null;
            player = null;
        }
    }

    private void startPlayerListener() {
        if (player != null) player.setFragmentListener(this);
    }

    private void stopPlayerListener() {
        if (player != null) player.removeFragmentListener(this);
    }

    private void startService(boolean playAfterConnect) {
        getContext().startService(new Intent(getContext(), MainPlayer.class));
        serviceConnection = getServiceConnection(playAfterConnect);
        bind();
    }

    private void stopService() {
        getContext().stopService(new Intent(getContext(), MainPlayer.class));
        unbind();
    }


    /*////////////////////////////////////////////////////////////////////////*/

    public static VideoDetailFragment getInstance(int serviceId, String videoUrl, String name, PlayQueue playQueue) {
        VideoDetailFragment instance = new VideoDetailFragment();
        instance.setInitialData(serviceId, videoUrl, name, playQueue);
        return instance;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        // Let's play all streams automatically
        setAutoplay(true);

        activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);

        showRelatedStreams = PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(getString(R.string.show_next_video_key), true);

        showComments = PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(getString(R.string.show_comments_key), true);

        selectedTabTag = PreferenceManager.getDefaultSharedPreferences(activity)
                .getString(getString(R.string.stream_info_selected_tab_key), COMMENTS_TAB_TAG);

        PreferenceManager.getDefaultSharedPreferences(activity)
                .registerOnSharedPreferenceChangeListener(this);

        startService(false);
        setupBroadcastReceiver();

        settingsContentObserver = new SettingsContentObserver(new Handler(), this);
        activity.getContentResolver().registerContentObserver(
                android.provider.Settings.System.CONTENT_URI, true,
                settingsContentObserver);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_video_detail, container, false);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (currentWorker != null) currentWorker.dispose();

        setupBrightness(true);
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit()
                .putString(getString(R.string.stream_info_selected_tab_key), pageAdapter.getItemTitle(viewPager.getCurrentItem()))
                .apply();
    }

    @Override
    public void onResume() {
        super.onResume();

        isFragmentStopped = false;

        setupBrightness(false);

        if (updateFlags != 0) {
            if (!isLoading.get() && currentInfo != null) {
                if ((updateFlags & RELATED_STREAMS_UPDATE_FLAG) != 0) startLoading(false);
                if ((updateFlags & RESOLUTIONS_MENU_UPDATE_FLAG) != 0) setupActionBar(currentInfo);
                if ((updateFlags & COMMENTS_UPDATE_FLAG) != 0) startLoading(false);
            }

            if ((updateFlags & TOOLBAR_ITEMS_UPDATE_FLAG) != 0
                    && menu != null) {
                updateMenuItemVisibility();
            }

            updateFlags = 0;
        }

        // Check if it was loading when the fragment was stopped/paused,
        if (wasLoading.getAndSet(false) && !wasCleared()) {
            selectAndLoadVideo(serviceId, url, name, playQueue);
        } else if (currentInfo != null) {
            updateProgressInfo(currentInfo);
        }

        if (player != null && player.videoPlayerSelected()) addVideoPlayerView();
    }

    @Override
    public void onStop() {
        super.onStop();

        isFragmentStopped = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (!activity.isFinishing()) unbind();
        else stopService();

        PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(this);
        activity.unregisterReceiver(broadcastReceiver);
        activity.getContentResolver().unregisterContentObserver(settingsContentObserver);

        if (positionSubscriber != null) positionSubscriber.dispose();
        if (currentWorker != null) currentWorker.dispose();
        if (disposables != null) disposables.clear();
        positionSubscriber = null;
        currentWorker = null;
        disposables = null;
    }

    @Override
    public void onDestroyView() {
        if (DEBUG) Log.d(TAG, "onDestroyView() called");
        spinnerToolbar.setOnItemSelectedListener(null);
        spinnerToolbar.setAdapter(null);
        super.onDestroyView();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case ReCaptchaActivity.RECAPTCHA_REQUEST:
                if (resultCode == Activity.RESULT_OK) {
                    NavigationHelper.openVideoDetailFragment(getFragmentManager(), serviceId, url, name);
                } else Log.e(TAG, "ReCaptcha failed");
                break;
            default:
                Log.e(TAG, "Request code from activity not supported [" + requestCode + "]");
                break;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(getString(R.string.show_next_video_key))) {
            showRelatedStreams = sharedPreferences.getBoolean(key, true);
            updateFlags |= RELATED_STREAMS_UPDATE_FLAG;
        } else if (key.equals(getString(R.string.default_video_format_key))
                || key.equals(getString(R.string.default_resolution_key))
                || key.equals(getString(R.string.show_higher_resolutions_key))
                || key.equals(getString(R.string.use_external_video_player_key))) {
            updateFlags |= RESOLUTIONS_MENU_UPDATE_FLAG;
        } else if (key.equals(getString(R.string.show_play_with_kodi_key))) {
            updateFlags |= TOOLBAR_ITEMS_UPDATE_FLAG;
        } else if (key.equals(getString(R.string.show_comments_key))) {
            showComments = sharedPreferences.getBoolean(key, true);
            updateFlags |= COMMENTS_UPDATE_FLAG;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    //////////////////////////////////////////////////////////////////////////*/

    private static final String INFO_KEY = "info_key";
    private static final String STACK_KEY = "stack_key";

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // Check if the next video label and video is visible,
        // if it is, include the two elements in the next check
        int nextCount = currentInfo != null && currentInfo.getNextVideo() != null ? 2 : 0;

        if (!isLoading.get() && currentInfo != null && isVisible()) {
            outState.putSerializable(INFO_KEY, currentInfo);
        }

        if (playQueue != null) outState.putSerializable(VideoPlayer.PLAY_QUEUE_KEY, playQueue);
        outState.putSerializable(STACK_KEY, stack);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedState) {
        super.onRestoreInstanceState(savedState);

        Serializable serializable = savedState.getSerializable(INFO_KEY);
        if (serializable instanceof StreamInfo) {
            //noinspection unchecked
            currentInfo = (StreamInfo) serializable;
            InfoCache.getInstance().putInfo(serviceId, url, currentInfo, InfoItem.InfoType.STREAM);
        }

        serializable = savedState.getSerializable(STACK_KEY);
        if (serializable instanceof Collection) {
            //noinspection unchecked
            stack.addAll((Collection<? extends StackItem>) serializable);
        }
        playQueue = (PlayQueue) savedState.getSerializable(VideoPlayer.PLAY_QUEUE_KEY);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // OnClick
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onClick(View v) {
        if (isLoading.get() || currentInfo == null) return;

        switch (v.getId()) {
            case R.id.detail_controls_background:
                openBackgroundPlayer(false);
                break;
            case R.id.detail_controls_popup:
                openPopupPlayer(false);
                break;
            case R.id.detail_controls_playlist_append:
                if (getFragmentManager() != null && currentInfo != null) {
                    PlaylistAppendDialog.fromStreamInfo(currentInfo)
                            .show(getFragmentManager(), TAG);
                }
                break;
            case R.id.detail_controls_download:
                if (PermissionHelper.checkStoragePermissions(activity,
                        PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
                    this.openDownloadDialog();
                }
                break;
            case R.id.detail_uploader_root_layout:
                openChannel();
                break;
            case R.id.detail_thumbnail_root_layout:
                openVideoPlayer();
                break;
            case R.id.detail_title_root_layout:
                toggleTitleAndDescription();
                break;
            case R.id.overlay_thumbnail:
            case R.id.overlay_metadata_layout:
            case R.id.overlay_buttons_layout:
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                break;
            case R.id.overlay_play_pause_button:
                if (player != null) {
                    player.onPlayPause();
                    player.hideControls(0,0);
                    showSystemUi();
                }
                else openVideoPlayer();

                setOverlayPlayPauseImage();
                break;
            case R.id.overlay_close_button:
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                break;
        }
    }

    @Override
    public boolean onLongClick(View v) {
        if (isLoading.get() || currentInfo == null) return false;

        switch (v.getId()) {
            case R.id.detail_controls_background:
                openBackgroundPlayer(true);
                break;
            case R.id.detail_controls_popup:
                openPopupPlayer(true);
                break;
            case R.id.detail_controls_download:
                NavigationHelper.openDownloads(activity);
                break;
            case R.id.overlay_thumbnail:
            case R.id.overlay_metadata_layout:
                openChannel();
                break;
        }

        return true;
    }

    private void toggleTitleAndDescription() {
        if (videoDescriptionRootLayout.getVisibility() == View.VISIBLE) {
            videoTitleTextView.setMaxLines(1);
            videoDescriptionRootLayout.setVisibility(View.GONE);
            videoTitleToggleArrow.setImageResource(R.drawable.arrow_down);
        } else {
            videoTitleTextView.setMaxLines(10);
            videoDescriptionRootLayout.setVisibility(View.VISIBLE);
            videoTitleToggleArrow.setImageResource(R.drawable.arrow_up);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void initViews(View rootView, Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
        spinnerToolbar = activity.findViewById(R.id.toolbar).findViewById(R.id.toolbar_spinner);

        thumbnailBackgroundButton = rootView.findViewById(R.id.detail_thumbnail_root_layout);
        thumbnailImageView = rootView.findViewById(R.id.detail_thumbnail_image_view);
        thumbnailPlayButton = rootView.findViewById(R.id.detail_thumbnail_play_button);

        contentRootLayoutHiding = rootView.findViewById(R.id.detail_content_root_hiding);

        videoTitleRoot = rootView.findViewById(R.id.detail_title_root_layout);
        videoTitleTextView = rootView.findViewById(R.id.detail_video_title_view);
        videoTitleToggleArrow = rootView.findViewById(R.id.detail_toggle_description_view);
        videoCountView = rootView.findViewById(R.id.detail_view_count_view);
        positionView = rootView.findViewById(R.id.position_view);

        detailControlsBackground = rootView.findViewById(R.id.detail_controls_background);
        detailControlsPopup = rootView.findViewById(R.id.detail_controls_popup);
        detailControlsAddToPlaylist = rootView.findViewById(R.id.detail_controls_playlist_append);
        detailControlsDownload = rootView.findViewById(R.id.detail_controls_download);
        appendControlsDetail = rootView.findViewById(R.id.touch_append_detail);
        detailDurationView = rootView.findViewById(R.id.detail_duration_view);
        detailPositionView = rootView.findViewById(R.id.detail_position_view);

        videoDescriptionRootLayout = rootView.findViewById(R.id.detail_description_root_layout);
        videoUploadDateView = rootView.findViewById(R.id.detail_upload_date_view);
        videoDescriptionView = rootView.findViewById(R.id.detail_description_view);
        videoDescriptionView.setMovementMethod(LinkMovementMethod.getInstance());
        videoDescriptionView.setAutoLinkMask(Linkify.WEB_URLS);

        thumbsUpTextView = rootView.findViewById(R.id.detail_thumbs_up_count_view);
        thumbsUpImageView = rootView.findViewById(R.id.detail_thumbs_up_img_view);
        thumbsDownTextView = rootView.findViewById(R.id.detail_thumbs_down_count_view);
        thumbsDownImageView = rootView.findViewById(R.id.detail_thumbs_down_img_view);
        thumbsDisabledTextView = rootView.findViewById(R.id.detail_thumbs_disabled_view);

        uploaderRootLayout = rootView.findViewById(R.id.detail_uploader_root_layout);
        uploaderTextView = rootView.findViewById(R.id.detail_uploader_text_view);
        uploaderThumb = rootView.findViewById(R.id.detail_uploader_thumbnail_view);

        overlay = rootView.findViewById(R.id.overlay_layout);
        overlayMetadata = rootView.findViewById(R.id.overlay_metadata_layout);
        overlayThumbnailImageView = rootView.findViewById(R.id.overlay_thumbnail);
        overlayTitleTextView = rootView.findViewById(R.id.overlay_title_text_view);
        overlayChannelTextView = rootView.findViewById(R.id.overlay_channel_text_view);
        overlayButtons = rootView.findViewById(R.id.overlay_buttons_layout);
        overlayPlayPauseButton = rootView.findViewById(R.id.overlay_play_pause_button);
        overlayCloseButton = rootView.findViewById(R.id.overlay_close_button);

        appBarLayout = rootView.findViewById(R.id.appbarlayout);
        viewPager = rootView.findViewById(R.id.viewpager);
        pageAdapter = new TabAdaptor(getChildFragmentManager());
        viewPager.setAdapter(pageAdapter);
        tabLayout = rootView.findViewById(R.id.tablayout);
        tabLayout.setupWithViewPager(viewPager);

        relatedStreamsLayout = rootView.findViewById(R.id.relatedStreamsLayout);

        setHeightThumbnail();
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        videoTitleRoot.setOnClickListener(this);
        uploaderRootLayout.setOnClickListener(this);
        thumbnailBackgroundButton.setOnClickListener(this);
        detailControlsBackground.setOnClickListener(this);
        detailControlsPopup.setOnClickListener(this);
        detailControlsAddToPlaylist.setOnClickListener(this);
        detailControlsDownload.setOnClickListener(this);
        detailControlsDownload.setOnLongClickListener(this);

        detailControlsBackground.setLongClickable(true);
        detailControlsPopup.setLongClickable(true);
        detailControlsBackground.setOnLongClickListener(this);
        detailControlsPopup.setOnLongClickListener(this);

        overlayThumbnailImageView.setOnClickListener(this);
        overlayThumbnailImageView.setOnLongClickListener(this);
        overlayMetadata.setOnClickListener(this);
        overlayMetadata.setOnLongClickListener(this);
        overlayButtons.setOnClickListener(this);
        overlayCloseButton.setOnClickListener(this);
        overlayPlayPauseButton.setOnClickListener(this);

        detailControlsBackground.setOnTouchListener(getOnControlsTouchListener());
        detailControlsPopup.setOnTouchListener(getOnControlsTouchListener());

        setupBottomPlayer();
    }

    private View.OnTouchListener getOnControlsTouchListener() {
        return (View view, MotionEvent motionEvent) -> {
            if (!PreferenceManager.getDefaultSharedPreferences(activity)
                    .getBoolean(getString(R.string.show_hold_to_append_key), true)) {
                return false;
            }

            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                animateView(appendControlsDetail, true, 250, 0, () ->
                        animateView(appendControlsDetail, false, 1500, 1000));
            }
            return false;
        };
    }

    private void initThumbnailViews(@NonNull StreamInfo info) {
        thumbnailImageView.setImageResource(R.drawable.dummy_thumbnail_dark);
        overlayThumbnailImageView.setImageResource(R.drawable.dummy_thumbnail_dark);

        if (!TextUtils.isEmpty(info.getThumbnailUrl())) {
            final String infoServiceName = NewPipe.getNameOfService(info.getServiceId());
            final ImageLoadingListener loadingListener = new SimpleImageLoadingListener() {
                @Override
                public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
                    showSnackBarError(failReason.getCause(), UserAction.LOAD_IMAGE,
                            infoServiceName, imageUri, R.string.could_not_load_thumbnails);
                }

                @Override
                public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
                    overlayThumbnailImageView.setImageBitmap(loadedImage);
                }
            };

            imageLoader.displayImage(info.getThumbnailUrl(), thumbnailImageView,
                    ImageDisplayConstants.DISPLAY_THUMBNAIL_OPTIONS, loadingListener);
        }

        if (!TextUtils.isEmpty(info.getUploaderAvatarUrl())) {
            imageLoader.displayImage(info.getUploaderAvatarUrl(), uploaderThumb,
                    ImageDisplayConstants.DISPLAY_AVATAR_OPTIONS);
        }
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        this.menu = menu;

        // CAUTION set item properties programmatically otherwise it would not be accepted by
        // appcompat itemsinflater.inflate(R.menu.videoitem_detail, menu);

        /*inflater.inflate(R.menu.video_detail_menu, menu);

        updateMenuItemVisibility();

        ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.setDisplayHomeAsUpEnabled(true);
            supportActionBar.setDisplayShowTitleEnabled(false);
        }*/
    }

    private void updateMenuItemVisibility() {
        /*// show kodi if set in settings
        menu.findItem(R.id.action_play_with_kodi).setVisible(
                PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(
                        activity.getString(R.string.show_play_with_kodi_key), false));*/
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (isLoading.get()) {
            // if is still loading block menu
            return true;
        }

        int id = item.getItemId();
        switch (id) {
            case R.id.menu_item_share: {
                if (currentInfo != null) {
                    ShareUtils.shareUrl(this.getContext(), currentInfo.getName(), currentInfo.getOriginalUrl());
                }
                return true;
            }
            case R.id.menu_item_openInBrowser: {
                if (currentInfo != null) {
                    ShareUtils.openUrlInBrowser(this.getContext(), currentInfo.getOriginalUrl());
                }
                return true;
            }
            case R.id.action_play_with_kodi:
                /*try {
                    NavigationHelper.playWithKore(activity, Uri.parse(
                            url.replace("https", "http")));
                } catch (Exception e) {
                    if (DEBUG) Log.i(TAG, "Failed to start kore", e);
                    showInstallKoreDialog(activity);
                }*/
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /*private static void showInstallKoreDialog(final Context context) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(R.string.kore_not_found)
                .setPositiveButton(R.string.install, (DialogInterface dialog, int which) ->
                        NavigationHelper.installKore(context))
                .setNegativeButton(R.string.cancel, (DialogInterface dialog, int which) -> {
                });
        builder.create().show();
    }

    private void setupActionBarOnError(final String url) {
        if (DEBUG) Log.d(TAG, "setupActionBarHandlerOnError() called with: url = [" + url + "]");
        Log.e("-----", "missing code");
    }*/

    private void setupActionBar(final StreamInfo info) {
        if (DEBUG) Log.d(TAG, "setupActionBarHandler() called with: info = [" + info + "]");
        boolean isExternalPlayerEnabled = PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(activity.getString(R.string.use_external_video_player_key), false);

        sortedVideoStreams = ListHelper.getSortedStreamVideosList(
                activity,
                info.getVideoStreams(),
                info.getVideoOnlyStreams(),
                false);
        selectedVideoStreamIndex = ListHelper.getDefaultResolutionIndex(activity, sortedVideoStreams);

        /*final StreamItemAdapter<VideoStream, Stream> streamsAdapter =
                new StreamItemAdapter<>(activity,
                        new StreamSizeWrapper<>(sortedVideoStreams, activity), isExternalPlayerEnabled);

        spinnerToolbar.setAdapter(streamsAdapter);
        spinnerToolbar.setSelection(selectedVideoStreamIndex);
        spinnerToolbar.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedVideoStreamIndex = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });*/
    }

    /*//////////////////////////////////////////////////////////////////////////
    // OwnStack
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Stack that contains the "navigation history".<br>
     * The peek is the current video.
     */
    protected final LinkedList<StackItem> stack = new LinkedList<>();

    public void setTitleToUrl(int serviceId, String videoUrl, String name) {
        if (name != null && !name.isEmpty()) {
            for (StackItem stackItem : stack) {
                if (stack.peek().getServiceId() == serviceId
                        && stackItem.getUrl().equals(videoUrl)) {
                    stackItem.setTitle(name);
                }
            }
        }
    }

    @Override
    public boolean onBackPressed() {
        if (DEBUG) Log.d(TAG, "onBackPressed() called");

        // If we are in fullscreen mode just exit from it via first back press
        if (player != null && player.isInFullscreen()) {
            player.onPause();
            restoreDefaultOrientation();
            return true;
        }

        StackItem currentPeek = stack.peek();
        if (currentPeek != null && currentPeek.getPlayQueue() != playQueue) {
            // When user selected a stream but didn't start playback this stream will not be added to backStack.
            // Then he press Back and the last saved item from history will show up
            setupFromHistoryItem(currentPeek);
            return true;
        }

        // If we have something in history of played items we replay it here
        if (player != null && player.getPlayQueue() != null && player.getPlayQueue().previous()) {
            return true;
        }
        // That means that we are on the start of the stack,
        // return false to let the MainActivity handle the onBack
        if (stack.size() <= 1) {
            restoreDefaultOrientation();

            return false;
        }
        // Remove top
        stack.pop();
        // Get stack item from the new top
        setupFromHistoryItem(stack.peek());

        return true;
    }

    private void setupFromHistoryItem(StackItem item) {
        hideMainPlayer();

        setAutoplay(false);
        selectAndLoadVideo(
                item.getServiceId(),
                item.getUrl(),
                !TextUtils.isEmpty(item.getTitle()) ? item.getTitle() : "",
                item.getPlayQueue());
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Info loading and handling
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void doInitialLoadLogic() {
        if (wasCleared()) return;

        if (currentInfo == null) prepareAndLoadInfo();
        else prepareAndHandleInfo(currentInfo, false);
    }

    public void selectAndLoadVideo(int serviceId, String videoUrl, String name, PlayQueue playQueue) {
        boolean streamIsTheSame = this.playQueue != null && this.playQueue.equals(playQueue);
        // Situation when user switches from players to main player. All needed data is here, we can start watching
        if (streamIsTheSame) {
            //TODO not sure about usefulness of this line in the case when user switches from one player to another
            // handleResult(currentInfo);
            openVideoPlayer();
            return;
        }
        setInitialData(serviceId, videoUrl, name, playQueue);
        startLoading(false);
    }

    public void prepareAndHandleInfo(final StreamInfo info, boolean scrollToTop) {
        if (DEBUG) Log.d(TAG, "prepareAndHandleInfo() called with: info = ["
                + info + "], scrollToTop = [" + scrollToTop + "]");

        showLoading();
        initTabs();

        if (scrollToTop) scrollToTop();
        handleResult(info);
        showContent();

    }

    protected void prepareAndLoadInfo() {
        scrollToTop();
        startLoading(false);
    }

    @Override
    public void startLoading(boolean forceLoad) {
        super.startLoading(forceLoad);

        initTabs();
        currentInfo = null;
        if (currentWorker != null) currentWorker.dispose();

        currentWorker = ExtractorHelper.getStreamInfo(serviceId, url, forceLoad)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((@NonNull StreamInfo result) -> {
                    isLoading.set(false);
                    hideMainPlayer();
                    handleResult(result);
                    showContent();
                    if (isAutoplayEnabled()) openVideoPlayer();
                }, (@NonNull Throwable throwable) -> {
                    isLoading.set(false);
                    onError(throwable);
                });

    }

    private void initTabs() {
        if (pageAdapter.getCount() != 0) {
            selectedTabTag = pageAdapter.getItemTitle(viewPager.getCurrentItem());
        }
        pageAdapter.clearAllItems();

        if(shouldShowComments()){
            pageAdapter.addFragment(CommentsFragment.getInstance(serviceId, url, name), COMMENTS_TAB_TAG);
        }

        if(showRelatedStreams && null == relatedStreamsLayout){
            //temp empty fragment. will be updated in handleResult
            pageAdapter.addFragment(new Fragment(), RELATED_TAB_TAG);
        }

        if(pageAdapter.getCount() == 0){
            pageAdapter.addFragment(new EmptyFragment(), EMPTY_TAB_TAG);
        }

        pageAdapter.notifyDataSetUpdate();

        if(pageAdapter.getCount() < 2){
            tabLayout.setVisibility(View.GONE);
        }else{
            int position = pageAdapter.getItemPositionByTitle(selectedTabTag);
            if(position != -1) viewPager.setCurrentItem(position);
            tabLayout.setVisibility(View.VISIBLE);
        }
    }

    private boolean shouldShowComments() {
        try {
            return showComments && NewPipe.getService(serviceId)
                    .getServiceInfo()
                    .getMediaCapabilities()
                    .contains(COMMENTS);
        } catch (ExtractionException e) {
            return false;
        }
    }

    public void scrollToTop() {
        appBarLayout.setExpanded(true, true);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Play Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void openBackgroundPlayer(final boolean append) {
        AudioStream audioStream = currentInfo.getAudioStreams()
                .get(ListHelper.getDefaultAudioFormat(activity, currentInfo.getAudioStreams()));

        boolean useExternalAudioPlayer = PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(activity.getString(R.string.use_external_audio_player_key), false);

        //  If a user watched video inside fullscreen mode and than chose another player return to non-fullscreen mode
        if (player != null && player.isInFullscreen()) player.toggleFullscreen();

        if (!useExternalAudioPlayer && android.os.Build.VERSION.SDK_INT >= 16) {
            openNormalBackgroundPlayer(append);
        } else {
            startOnExternalPlayer(activity, currentInfo, audioStream);
        }
    }

    private void openPopupPlayer(final boolean append) {
        if (!PermissionHelper.isPopupEnabled(activity)) {
            PermissionHelper.showPopupEnablementToast(activity);
            return;
        }

        // See UI changes while remote playQueue changes
        if (!bounded) startService(false);

        //  If a user watched video inside fullscreen mode and than chose another player return to non-fullscreen mode
        if (player != null && player.isInFullscreen()) player.toggleFullscreen();

        PlayQueue queue = setupPlayQueueForIntent(append);
        if (append) {
            NavigationHelper.enqueueOnPopupPlayer(activity, queue, false);
        } else {
            NavigationHelper.playOnPopupPlayer(activity, queue, true);
        }
    }

    private void openVideoPlayer() {
        if (PreferenceManager.getDefaultSharedPreferences(activity)
                .getBoolean(this.getString(R.string.use_external_video_player_key), false)) {
            VideoStream selectedVideoStream = getSelectedVideoStream();
            startOnExternalPlayer(activity, currentInfo, selectedVideoStream);
        } else {
            openNormalPlayer();
        }
    }

    private void openNormalBackgroundPlayer(final boolean append) {
        // See UI changes while remote playQueue changes
        if (!bounded) startService(false);

        PlayQueue queue = setupPlayQueueForIntent(append);
        if (append) {
            NavigationHelper.enqueueOnBackgroundPlayer(activity, queue, false);
        } else {
            NavigationHelper.playOnBackgroundPlayer(activity, queue, true);
        }
    }

    private void openNormalPlayer() {
        if (playerService == null) {
                startService(true);
                return;
            }
            if (currentInfo == null || playQueue == null)
                return;

            PlayQueue queue = setupPlayQueueForIntent(false);

            addVideoPlayerView();
            playerService.getView().setVisibility(View.GONE);

            Intent playerIntent = NavigationHelper.getPlayerIntent(
                    getContext(), MainPlayer.class, queue, null, true);
            activity.startService(playerIntent);
    }

    private void hideMainPlayer() {
        if (playerService == null || playerService.getView() == null || !player.videoPlayerSelected())
            return;

        removeVideoPlayerView();
        playerService.stop();
        playerService.getView().setVisibility(View.GONE);
    }

    private PlayQueue setupPlayQueueForIntent(boolean append) {
        if (append) return new SinglePlayQueue(currentInfo);

        PlayQueue queue = playQueue;
        // Size can be 0 because queue removes bad stream automatically when error occurs
        if (playQueue == null || playQueue.size() == 0)
            queue = new SinglePlayQueue(currentInfo);
        this.playQueue = queue;

        return queue;
    }

    private void openChannel() {
        if (TextUtils.isEmpty(currentInfo.getUploaderUrl())) {
            Log.w(TAG, "Can't open channel because we got no channel URL");
        } else {
            try {
                NavigationHelper.openChannelFragment(
                        getFragmentManager(),
                        currentInfo.getServiceId(),
                        currentInfo.getUploaderUrl(),
                        currentInfo.getUploaderName());
            } catch (Exception e) {
                ErrorActivity.reportUiError(activity, e);
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    public void setAutoplay(boolean autoplay) {
        this.autoPlayEnabled = autoplay;
    }

    private void startOnExternalPlayer(@NonNull final Context context,
                                       @NonNull final StreamInfo info,
                                       @NonNull final Stream selectedStream) {
        NavigationHelper.playOnExternalPlayer(context, currentInfo.getName(),
                currentInfo.getUploaderName(), selectedStream);

        final HistoryRecordManager recordManager = new HistoryRecordManager(requireContext());
        disposables.add(recordManager.onViewed(info).onErrorComplete()
                .subscribe(
                        ignored -> {/* successful */},
                        error -> Log.e(TAG, "Register view failure: ", error)
                ));
    }

    private boolean isExternalPlayerEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean(getString(R.string.use_external_video_player_key), false);
    }

    // This method overrides default behaviour when setAutoplay() is called.
    // Don't auto play if the user selected an external player or disabled it in settings
    private boolean isAutoplayEnabled() {
        return playQueue != null && playQueue.getStreams().size() != 0
                && autoPlayEnabled
                && !isExternalPlayerEnabled()
                && (player == null || player.videoPlayerSelected())
                && isAutoplayAllowedByUser();
    }

    private boolean isAutoplayAllowedByUser () {
        if (activity == null) return false;

        switch (PlayerHelper.getAutoplayType(activity)) {
            case PlayerHelper.AutoplayType.AUTOPLAY_TYPE_NEVER:
                return false;
            case PlayerHelper.AutoplayType.AUTOPLAY_TYPE_WIFI:
                return !ListHelper.isMeteredNetwork(activity);
            case PlayerHelper.AutoplayType.AUTOPLAY_TYPE_ALWAYS:
            default:
                return true;
        }
    }

    private void addVideoPlayerView() {
        if (player == null) return;

        FrameLayout viewHolder = getView().findViewById(R.id.player_placeholder);

        // Check if viewHolder already contains a child
        if (player.getRootView() != viewHolder) removeVideoPlayerView();
        setHeightThumbnail();

        // Prevent from re-adding a view multiple times
        if (player.getRootView().getParent() == null) viewHolder.addView(player.getRootView());
    }

    private void removeVideoPlayerView() {
        makeDefaultHeightForVideoPlaceholder();

        playerService.removeViewFromParent();
    }

    private void makeDefaultHeightForVideoPlaceholder() {
        FrameLayout viewHolder = getView().findViewById(R.id.player_placeholder);
        viewHolder.getLayoutParams().height = FrameLayout.LayoutParams.MATCH_PARENT;
        viewHolder.requestLayout();
    }


    @Nullable
    private VideoStream getSelectedVideoStream() {
        return sortedVideoStreams != null ? sortedVideoStreams.get(selectedVideoStreamIndex) : null;
    }

    private void prepareDescription(final String descriptionHtml) {
        if (TextUtils.isEmpty(descriptionHtml)) {
            return;
        }

        disposables.add(Single.just(descriptionHtml)
                .map((@io.reactivex.annotations.NonNull String description) -> {
                    Spanned parsedDescription;
                    if (Build.VERSION.SDK_INT >= 24) {
                        parsedDescription = Html.fromHtml(description, 0);
                    } else {
                        //noinspection deprecation
                        parsedDescription = Html.fromHtml(description);
                    }
                    return parsedDescription;
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((@io.reactivex.annotations.NonNull Spanned spanned) -> {
                    videoDescriptionView.setText(spanned);
                    videoDescriptionView.setVisibility(View.VISIBLE);
                }));
    }

    private void setHeightThumbnail() {
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean isPortrait = metrics.heightPixels > metrics.widthPixels;

        int height;
        if (player != null && player.isInFullscreen())
            height = isInMultiWindow() ? getView().getHeight() : activity.getWindow().getDecorView().getHeight();
        else
            height = isPortrait
                    ? (int) (metrics.widthPixels / (16.0f / 9.0f))
                    : (int) (metrics.heightPixels / 2f);;

        thumbnailImageView.setLayoutParams(
                new FrameLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, height));
        thumbnailImageView.setMinimumHeight(height);
    }

    private void showContent() {
        contentRootLayoutHiding.setVisibility(View.VISIBLE);
    }

    protected void setInitialData(int serviceId, String url, String name, PlayQueue playQueue) {
        this.serviceId = serviceId;
        this.url = url;
        this.name = !TextUtils.isEmpty(name) ? name : "";
        this.playQueue = playQueue;
    }

    private void setErrorImage(final int imageResource) {
        if (thumbnailImageView == null || activity == null) return;

        thumbnailImageView.setImageDrawable(ContextCompat.getDrawable(activity, imageResource));
        animateView(thumbnailImageView, false, 0, 0,
                () -> animateView(thumbnailImageView, true, 500));
    }

    @Override
    public void showError(String message, boolean showRetryButton) {
        showError(message, showRetryButton, R.drawable.not_available_monkey);
    }

    protected void showError(String message, boolean showRetryButton, @DrawableRes int imageError) {
        super.showError(message, showRetryButton);
        setErrorImage(imageError);
    }

    private void setupBroadcastReceiver() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(intent.getAction().equals(ACTION_SHOW_MAIN_PLAYER)) {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                } else if(intent.getAction().equals(ACTION_HIDE_MAIN_PLAYER)) {
                    bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_SHOW_MAIN_PLAYER);
        intentFilter.addAction(ACTION_HIDE_MAIN_PLAYER);
        activity.registerReceiver(broadcastReceiver, intentFilter);
    }


    /*//////////////////////////////////////////////////////////////////////////
    // Orientation listener
    //////////////////////////////////////////////////////////////////////////*/

    private boolean globalScreenOrientationLocked() {
        // 1: Screen orientation changes using accelerometer
        // 0: Screen orientation is locked
        return !(android.provider.Settings.System.getInt(
                activity.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1);
    }

    private void restoreDefaultOrientation() {
        if (player == null || !player.videoPlayerSelected() || activity == null) return;

        if (player != null && player.isInFullscreen()) player.toggleFullscreen();
        // This will show systemUI and pause the player.
        // User can tap on Play button and video will be in fullscreen mode again
        if (globalScreenOrientationLocked()) removeVideoPlayerView();
        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void setupOrientation() {
        if (player == null || !player.videoPlayerSelected() || activity == null) return;

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
        int newOrientation;
        if (globalScreenOrientationLocked()) {
            boolean lastOrientationWasLandscape
                    = sharedPreferences.getBoolean(getString(R.string.last_orientation_landscape_key), false);
            newOrientation = lastOrientationWasLandscape
                    ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        } else
            newOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

        if (newOrientation != activity.getRequestedOrientation())
            activity.setRequestedOrientation(newOrientation);
    }

    @Override
    public void onSettingsChanged() {
        if(activity != null && !globalScreenOrientationLocked())
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {

        super.showLoading();

        //if data is already cached, transition from VISIBLE -> INVISIBLE -> VISIBLE is not required
        if(!ExtractorHelper.isCached(serviceId, url, InfoItem.InfoType.STREAM)){
            contentRootLayoutHiding.setVisibility(View.INVISIBLE);
        }

        //animateView(spinnerToolbar, false, 200);
        animateView(thumbnailPlayButton, false, 50);
        animateView(detailDurationView, false, 100);
        animateView(detailPositionView, false, 100);
        animateView(positionView, false, 50);

        videoTitleTextView.setText(name != null ? name : "");
        videoTitleTextView.setMaxLines(1);
        animateView(videoTitleTextView, true, 0);

        videoDescriptionRootLayout.setVisibility(View.GONE);
        videoTitleToggleArrow.setImageResource(R.drawable.arrow_down);
        videoTitleToggleArrow.setVisibility(View.GONE);
        videoTitleRoot.setClickable(false);

        if(relatedStreamsLayout != null){
            if(showRelatedStreams){
                relatedStreamsLayout.setVisibility(View.INVISIBLE);
            }else{
                relatedStreamsLayout.setVisibility(View.GONE);
            }
        }

        imageLoader.cancelDisplayTask(thumbnailImageView);
        imageLoader.cancelDisplayTask(uploaderThumb);
        thumbnailImageView.setImageBitmap(null);
        uploaderThumb.setImageBitmap(null);
    }

    @Override
    public void handleResult(@NonNull StreamInfo info) {
        super.handleResult(info);

        currentInfo = info;
        setInitialData(info.getServiceId(), info.getOriginalUrl(), info.getName(),
                playQueue == null ? new SinglePlayQueue(info) : playQueue);

        if(showRelatedStreams){
            if(null == relatedStreamsLayout){ //phone
                pageAdapter.updateItem(RELATED_TAB_TAG, RelatedVideosFragment.getInstance(info));
                pageAdapter.notifyDataSetUpdate();
            }else{ //tablet
                getChildFragmentManager().beginTransaction()
                        .replace(R.id.relatedStreamsLayout, RelatedVideosFragment.getInstance(info))
                        .commitNow();
                relatedStreamsLayout.setVisibility(View.VISIBLE);
            }
        }

        animateView(thumbnailPlayButton, true, 200);
        videoTitleTextView.setText(name);
        overlayTitleTextView.setText(name);

        if (!TextUtils.isEmpty(info.getUploaderName())) {
            uploaderTextView.setText(info.getUploaderName());
            uploaderTextView.setVisibility(View.VISIBLE);
            uploaderTextView.setSelected(true);
            overlayChannelTextView.setText(info.getUploaderName());
        } else {
            uploaderTextView.setVisibility(View.GONE);
        }
        uploaderThumb.setImageDrawable(ContextCompat.getDrawable(activity, R.drawable.buddy));

        if (info.getViewCount() >= 0) {
            if (info.getStreamType().equals(StreamType.AUDIO_LIVE_STREAM)) {
                videoCountView.setText(Localization.listeningCount(activity, info.getViewCount()));
            } else if (info.getStreamType().equals(StreamType.LIVE_STREAM)) {
                videoCountView.setText(Localization.watchingCount(activity, info.getViewCount()));
            } else {
                videoCountView.setText(Localization.localizeViewCount(activity, info.getViewCount()));
            }
            videoCountView.setVisibility(View.VISIBLE);
        } else {
            videoCountView.setVisibility(View.GONE);
        }

        if (info.getDislikeCount() == -1 && info.getLikeCount() == -1) {
            thumbsDownImageView.setVisibility(View.VISIBLE);
            thumbsUpImageView.setVisibility(View.VISIBLE);
            thumbsUpTextView.setVisibility(View.GONE);
            thumbsDownTextView.setVisibility(View.GONE);

            thumbsDisabledTextView.setVisibility(View.VISIBLE);
        } else {
            if (info.getDislikeCount() >= 0) {
                thumbsDownTextView.setText(Localization.shortCount(activity, info.getDislikeCount()));
                thumbsDownTextView.setVisibility(View.VISIBLE);
                thumbsDownImageView.setVisibility(View.VISIBLE);
            } else {
                thumbsDownTextView.setVisibility(View.GONE);
                thumbsDownImageView.setVisibility(View.GONE);
            }

            if (info.getLikeCount() >= 0) {
                thumbsUpTextView.setText(Localization.shortCount(activity, info.getLikeCount()));
                thumbsUpTextView.setVisibility(View.VISIBLE);
                thumbsUpImageView.setVisibility(View.VISIBLE);
            } else {
                thumbsUpTextView.setVisibility(View.GONE);
                thumbsUpImageView.setVisibility(View.GONE);
            }
            thumbsDisabledTextView.setVisibility(View.GONE);
        }

        if (info.getDuration() > 0) {
            detailDurationView.setText(Localization.getDurationString(info.getDuration()));
            detailDurationView.setBackgroundColor(
                    ContextCompat.getColor(activity, R.color.duration_background_color));
            animateView(detailDurationView, true, 100);
        } else if (info.getStreamType() == StreamType.LIVE_STREAM) {
            detailDurationView.setText(R.string.duration_live);
            detailDurationView.setBackgroundColor(
                    ContextCompat.getColor(activity, R.color.live_duration_background_color));
            animateView(detailDurationView, true, 100);
        } else {
            detailDurationView.setVisibility(View.GONE);
        }

        videoDescriptionView.setVisibility(View.GONE);
        videoTitleRoot.setClickable(true);
        videoTitleToggleArrow.setVisibility(View.VISIBLE);
        videoTitleToggleArrow.setImageResource(R.drawable.arrow_down);
        videoDescriptionRootLayout.setVisibility(View.GONE);

        if (info.getUploadDate() != null) {
            videoUploadDateView.setText(Localization.localizeUploadDate(activity, info.getUploadDate().date().getTime()));
            videoUploadDateView.setVisibility(View.VISIBLE);
        } else {
            videoUploadDateView.setText(null);
            videoUploadDateView.setVisibility(View.GONE);
        }

        prepareDescription(info.getDescription());
        updateProgressInfo(info);

        //animateView(spinnerToolbar, true, 500);
        setupActionBar(info);
        initThumbnailViews(info);

        setTitleToUrl(info.getServiceId(), info.getUrl(), info.getName());
        setTitleToUrl(info.getServiceId(), info.getOriginalUrl(), info.getName());

        if (!info.getErrors().isEmpty()) {
            showSnackBarError(info.getErrors(),
                    UserAction.REQUESTED_STREAM,
                    NewPipe.getNameOfService(info.getServiceId()),
                    info.getUrl(),
                    0);
        }

        switch (info.getStreamType()) {
            case LIVE_STREAM:
            case AUDIO_LIVE_STREAM:
                detailControlsDownload.setVisibility(View.GONE);
                spinnerToolbar.setVisibility(View.GONE);
                break;
            default:
                if(info.getAudioStreams().isEmpty()) detailControlsBackground.setVisibility(View.GONE);
                if (!info.getVideoStreams().isEmpty()
                        || !info.getVideoOnlyStreams().isEmpty()) break;

                detailControlsPopup.setVisibility(View.GONE);
                spinnerToolbar.setVisibility(View.GONE);
                thumbnailPlayButton.setImageResource(R.drawable.ic_headset_white_24dp);
                break;
        }
    }


    public void openDownloadDialog() {
            try {
                DownloadDialog downloadDialog = DownloadDialog.newInstance(currentInfo);
                downloadDialog.setVideoStreams(sortedVideoStreams);
                downloadDialog.setAudioStreams(currentInfo.getAudioStreams());
                downloadDialog.setSelectedVideoStream(selectedVideoStreamIndex);
                downloadDialog.setSubtitleStreams(currentInfo.getSubtitles());

                downloadDialog.show(activity.getSupportFragmentManager(), "downloadDialog");
            } catch (Exception e) {
                ErrorActivity.ErrorInfo info = ErrorActivity.ErrorInfo.make(UserAction.UI_ERROR,
                        ServiceList.all()
                                .get(currentInfo
                                        .getServiceId())
                                .getServiceInfo()
                                .getName(), "",
                        R.string.could_not_setup_download_menu);

                ErrorActivity.reportError(activity,
                        e,
                        activity.getClass(),
                        activity.findViewById(android.R.id.content), info);
            }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Stream Results
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected boolean onError(Throwable exception) {
        if (super.onError(exception)) return true;

        else if (exception instanceof ContentNotAvailableException) {
            showError(getString(R.string.content_not_available), false);
        } else {
            int errorId = exception instanceof YoutubeStreamExtractor.DecryptException
                    ? R.string.youtube_signature_decryption_error
                    : exception instanceof ParsingException
                    ? R.string.parsing_error
                    : R.string.general_error;
            onUnrecoverableError(exception,
                    UserAction.REQUESTED_STREAM,
                    NewPipe.getNameOfService(serviceId),
                    url,
                    errorId);
        }

        return true;
    }

    private void updateProgressInfo(@NonNull final StreamInfo info) {
        if (positionSubscriber != null) {
            positionSubscriber.dispose();
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final boolean playbackResumeEnabled =
                prefs.getBoolean(activity.getString(R.string.enable_watch_history_key), true)
                        && prefs.getBoolean(activity.getString(R.string.enable_playback_resume_key), true);
        final boolean showPlaybackPosition = prefs.getBoolean(
                activity.getString(R.string.enable_playback_state_lists_key), true);
        if (!playbackResumeEnabled || info.getDuration() <= 0) {
            if (playQueue == null || playQueue.getStreams().isEmpty()
                    || playQueue.getItem().getRecoveryPosition() == RECOVERY_UNSET || !showPlaybackPosition) {
                positionView.setVisibility(View.INVISIBLE);
                detailPositionView.setVisibility(View.GONE);
            } else {
                // Show saved position from backStack if user allows it
                showPlaybackProgress(playQueue.getItem().getRecoveryPosition(),
                        playQueue.getItem().getDuration() * 1000);
                animateView(positionView, true, 500);
                animateView(detailPositionView, true, 500);
            }
            return;
        }
        final HistoryRecordManager recordManager = new HistoryRecordManager(requireContext());
        positionSubscriber = recordManager.loadStreamState(info)
                .subscribeOn(Schedulers.io())
                .onErrorComplete()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(state -> {
                    showPlaybackProgress(state.getProgressTime(), info.getDuration() * 1000);
                    animateView(positionView, true, 500);
                    animateView(detailPositionView, true, 500);
                }, e -> {
                    if (DEBUG) e.printStackTrace();
                }, () -> {
                    // OnComplete, do nothing
                });
    }

    private void showPlaybackProgress(long progress, long duration) {
        final int progressSeconds = (int) TimeUnit.MILLISECONDS.toSeconds(progress);
        final int durationSeconds = (int) TimeUnit.MILLISECONDS.toSeconds(duration);
        positionView.setMax(durationSeconds);
        positionView.setProgress(progressSeconds);
        detailPositionView.setText(Localization.getDurationString(progressSeconds));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Player event listener
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onQueueUpdate(PlayQueue queue) {
        playQueue = queue;
        // This should be the only place where we push data to stack. It will allow to have live instance of PlayQueue with actual
        // information about deleted/added items inside Channel/Playlist queue and makes possible to have a history of played items
        if (stack.isEmpty() || !stack.peek().getPlayQueue().equals(queue))
            stack.push(new StackItem(serviceId, url, name, playQueue));

        if (DEBUG) {
            Log.d(TAG, "onQueueUpdate() called with: serviceId = ["
                    + serviceId + "], videoUrl = [" + url + "], name = [" + name + "], playQueue = [" + playQueue + "]");
        }
    }

    @Override
    public void onPlaybackUpdate(int state, int repeatMode, boolean shuffled, PlaybackParameters parameters) {
        setOverlayPlayPauseImage();

        switch (state) {
            case BasePlayer.STATE_COMPLETED:
                restoreDefaultOrientation();
                break;
            case BasePlayer.STATE_PLAYING:
                if (positionView.getAlpha() != 1f) animateView(positionView, true, 100);
                if (detailPositionView.getAlpha() != 1f) animateView(detailPositionView, true, 100);
                setupOrientation();
                break;
        }
    }

    @Override
    public void onProgressUpdate(int currentProgress, int duration, int bufferPercent) {
        // Progress updates every second even if media is paused. It's useless until playing
        if (!player.getPlayer().isPlaying() || playQueue == null) return;

        if (playQueue == player.getPlayQueue()) showPlaybackProgress(currentProgress, duration);

        // We don't want to interrupt playback and don't want to see notification if player is stopped
        // since next lines of code will enable background playback if needed
        if (!player.videoPlayerSelected()) return;

        // This will be called when user goes to another app
        if (isFragmentStopped) {
            // Video enabled. Let's think what to do with source in background
            if (player.backgroundPlaybackEnabled())
                player.useVideoSource(false);
            else if (player.minimizeOnPopupEnabled())
                NavigationHelper.playOnPopupPlayer(activity, playQueue, true);
            else player.onPause();
        }
        else player.useVideoSource(true);
    }

    @Override
    public void onMetadataUpdate(StreamInfo info) {
        if (!stack.isEmpty()) {
            // When PlayQueue can have multiple streams (PlaylistPlayQueue or ChannelPlayQueue) every new played stream gives
            // new title and url. StackItem contains information about first played stream. Let's update it here
            StackItem peek = stack.peek();
            peek.setTitle(info.getName());
            peek.setUrl(info.getUrl());
        }

        if (currentInfo == info) return;

        currentInfo = info;
        setAutoplay(false);
        prepareAndHandleInfo(info, true);
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        if (error.type == ExoPlaybackException.TYPE_SOURCE || error.type == ExoPlaybackException.TYPE_UNEXPECTED) {
            hideMainPlayer();
            if (playerService != null && player.isInFullscreen())
                player.toggleFullscreen();
        }
    }

    @Override
    public void onServiceStopped() {
        unbind();
        setOverlayPlayPauseImage();
    }

    @Override
    public void onFullscreenStateChanged(boolean fullscreen) {
        if (playerService.getView() == null || player.getParentActivity() == null)
            return;

        View view = playerService.getView();
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent == null) return;

        if (fullscreen) {
            hideSystemUIIfNeeded();
        } else {
            showSystemUi();
        }

        if (relatedStreamsLayout != null) relatedStreamsLayout.setVisibility(fullscreen ? View.GONE : View.VISIBLE);

        addVideoPlayerView();
    }

    /*
    * Will scroll down to description view after long click on moreOptionsButton
    * */
    @Override
    public void onMoreOptionsLongClicked() {
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        AppBarLayout.Behavior behavior = (AppBarLayout.Behavior) params.getBehavior();
        ValueAnimator valueAnimator = ValueAnimator.ofInt(0, -getView().findViewById(R.id.player_placeholder).getHeight());
        valueAnimator.setInterpolator(new DecelerateInterpolator());
        valueAnimator.addUpdateListener(animation -> {
            behavior.setTopAndBottomOffset((int) animation.getAnimatedValue());
            appBarLayout.requestLayout();
        });
        valueAnimator.setInterpolator(new DecelerateInterpolator());
        valueAnimator.setDuration(500);
        valueAnimator.start();
    }

    @Override
    public boolean isFragmentStopped() {
        return isFragmentStopped;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Player related utils
    //////////////////////////////////////////////////////////////////////////*/

    private void showSystemUi() {
        if (DEBUG) Log.d(TAG, "showSystemUi() called");

        if (activity == null) return;

        activity.getWindow().getDecorView().setSystemUiVisibility(0);
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
    }

    private void hideSystemUi() {
        if (DEBUG) Log.d(TAG, "hideSystemUi() called");

        if (activity == null) return;

        int visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        activity.getWindow().getDecorView().setSystemUiVisibility(visibility);
        activity.getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
    }

    // Listener implementation
    public void hideSystemUIIfNeeded() {
        if (player != null && player.isInFullscreen() && bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED)
            hideSystemUi();
    }

    private void setupBrightness(boolean save) {
        if (activity == null) return;

        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        float brightnessLevel;

        if (save) {
            // Save current brightness level
            PlayerHelper.setScreenBrightness(activity, lp.screenBrightness);

            // Restore the old  brightness when fragment.onPause() called.
            // It means when user leaves this fragment brightness will be set to system brightness
            lp.screenBrightness = -1;
        } else {
            // Restore already saved brightness level
            brightnessLevel = PlayerHelper.getScreenBrightness(activity);
            if (brightnessLevel <= 0.0f && brightnessLevel > 1.0f)
                return;

            lp.screenBrightness = brightnessLevel;
        }
        activity.getWindow().setAttributes(lp);
    }

    private void checkLandscape() {
        if ((!player.isPlaying() && player.getPlayQueue() != playQueue) || player.getPlayQueue() == null)
            setAutoplay(true);

        // Let's give a user time to look at video information page if video is not playing
        if (player.isPlaying())
            player.checkLandscape();
    }

    private boolean isLandscape() {
        return getResources().getDisplayMetrics().heightPixels < getResources().getDisplayMetrics().widthPixels;
    }

    private boolean isInMultiWindow() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInMultiWindowMode();
    }

    /*
    * Means that the player fragment was swiped away via BottomSheetLayout and is empty but ready for any new actions. See cleanUp()
    * */
    private boolean wasCleared() {
        return url == null;
    }

    /*
    * Remove unneeded information while waiting for a next task
    * */
    private void cleanUp() {
        // New beginning
        stack.clear();
        stopService();
        setInitialData(0,null,"", null);
        currentInfo = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Bottom mini player
    //////////////////////////////////////////////////////////////////////////*/

    private void setupBottomPlayer() {
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) appBarLayout.getLayoutParams();
        AppBarLayout.Behavior behavior = (AppBarLayout.Behavior) params.getBehavior();

        FrameLayout bottomSheetLayout = activity.findViewById(R.id.fragment_player_holder);
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetLayout);
        bottomSheetBehavior.setState(bottomSheetState);
        final int peekHeight = getResources().getDimensionPixelSize(R.dimen.mini_player_height);
        if (bottomSheetState != BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.setPeekHeight(peekHeight);
            if (bottomSheetState == BottomSheetBehavior.STATE_COLLAPSED)
                setOverlayLook(appBarLayout, behavior, 1 - MAX_OVERLAY_ALPHA);
            else if (bottomSheetState == BottomSheetBehavior.STATE_EXPANDED)
                setOverlayElementsClickable(false);
        }

        bottomSheetBehavior.setBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override public void onStateChanged(@NonNull View bottomSheet, int newState) {
                bottomSheetState = newState;

                switch (newState) {
                    case BottomSheetBehavior.STATE_HIDDEN:
                        bottomSheetBehavior.setPeekHeight(0);
                        cleanUp();
                        break;
                    case BottomSheetBehavior.STATE_EXPANDED:
                        bottomSheetBehavior.setPeekHeight(peekHeight);
                        // Disable click because overlay buttons located on top of buttons from the player
                        setOverlayElementsClickable(false);
                        hideSystemUIIfNeeded();
                        if (isLandscape() && player != null && player.isPlaying() && !player.isInFullscreen())
                            player.toggleFullscreen();
                        break;
                    case BottomSheetBehavior.STATE_COLLAPSED:
                        // Re-enable clicks
                        setOverlayElementsClickable(true);
                        if (player != null && player.isInFullscreen()) showSystemUi();
                        break;
                    case BottomSheetBehavior.STATE_DRAGGING:
                        if (player != null && player.isControlsVisible()) player.hideControls(0, 0);
                        break;
                    case BottomSheetBehavior.STATE_SETTLING:
                        break;
                }
            }
            @Override public void onSlide(@NonNull View bottomSheet, float slideOffset) {
                setOverlayLook(appBarLayout, behavior, slideOffset);
            }
        });

        // User opened a new page and the player will hide itself
        activity.getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (bottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED)
                bottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        });
    }

    private void setOverlayPlayPauseImage() {
        boolean playing = player != null && player.getPlayer().getPlayWhenReady();
        int attr = playing ? R.attr.pause : R.attr.play;
        overlayPlayPauseButton.setImageResource(ThemeHelper.resolveResourceIdFromAttr(activity, attr));
    }

    private void setOverlayLook(AppBarLayout appBarLayout, AppBarLayout.Behavior behavior, float slideOffset) {
        if (behavior != null) {
            overlay.setAlpha(Math.min(MAX_OVERLAY_ALPHA, 1 - slideOffset));

            behavior.setTopAndBottomOffset((int)(-appBarLayout.getTotalScrollRange() * (1 - slideOffset) / 3));
            appBarLayout.requestLayout();
        }
    }

    private void setOverlayElementsClickable(boolean enable) {
        overlayThumbnailImageView.setClickable(enable);
        overlayThumbnailImageView.setLongClickable(enable);
        overlayMetadata.setClickable(enable);
        overlayMetadata.setLongClickable(enable);
        overlayButtons.setClickable(enable);
        overlayPlayPauseButton.setClickable(enable);
        overlayCloseButton.setClickable(enable);
    }
}
