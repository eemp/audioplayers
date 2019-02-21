package xyz.luan.audioplayers;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.DefaultEventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.FileDataSource;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.PluginRegistry.Registrar;

public class WrappedMediaPlayer {

    private String playerId;

    private String url;
    private double volume = 1.0;
    private ReleaseMode releaseMode = ReleaseMode.RELEASE;

    private boolean released = true;
    private boolean prepared = false;
    private boolean playing = false;

    private double shouldSeekTo = -1;

    private SimpleExoPlayer player;
    private AudioplayersPlugin ref;

    public WrappedMediaPlayer(AudioplayersPlugin ref, String playerId) {
        this.ref = ref;
        this.playerId = playerId;
    }

    public void setUrl(String url) {
        if (!objectEquals(this.url, url)) {
            this.url = url;

            if (this.released) {
                this.player = createPlayer();
                this.released = false;
            } else if (this.prepared) {
                this.player.stop(true);
                this.prepared = false;
            }

            this.prepareSource(url);
            this.player.setVolume((float) volume);
            this.player.setRepeatMode(this.releaseMode == ReleaseMode.LOOP ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
            //this.player.setPlaybackParameters(new PlaybackParameters([> speed= */ (float) 0.5, /* pitch= */ 1, /* skipSilence= <] true));
        }
    }

    public String getUrl() {
        return this.url;
    }

    public void setVolume(double volume) {
        if (this.volume != volume) {
            this.volume = volume;
            if (!this.released) {
                this.player.setVolume((float) volume);
            }
        }
    }

    public double getVolume() {
        return this.volume;
    }

    public boolean isPlaying() {
        return this.playing;
    }

    public boolean isActuallyPlaying() {
        return this.playing && this.prepared;
    }

    public void play() {
        if (!this.playing) {
            this.playing = true;
            if (this.released) {
                this.released = false;
                this.player = createPlayer();
                this.prepareSource(url);
            } else if (this.prepared) {
                this.player.setPlayWhenReady(true);
                this.ref.handleIsPlaying(this);
            }
        }
    }

    public void stop() {
        if (this.released) {
            return;
        }

        if (releaseMode != ReleaseMode.RELEASE) {
            if (this.playing) {
                this.playing = false;
                this.player.setPlayWhenReady(false);
                this.player.seekTo(0);
            }
        } else {
            this.release();
        }
    }

    public void release() {
        if (this.released) {
            return;
        }

        if (this.playing) {
            this.player.setPlayWhenReady(false);
        }
        this.player.release();
        this.player = null;

        this.prepared = false;
        this.released = true;
        this.playing = false;
    }

    public void pause() {
        if (this.playing) {
            this.playing = false;
            this.player.setPlayWhenReady(false);
        }
    }

    private void prepareSource(String url) {
        try {
            Uri uri = Uri.parse(url);
            DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this.ref.registrar.context(), "ExoPlayer");
            MediaSource mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(uri);
            this.player.prepare(mediaSource);
        } catch (Exception ex) {
            throw new RuntimeException("Unable to access resource", ex);
        }
    }

    // seek operations cannot be called until after
    // the player is ready.
    public void seek(double position) {
        if (this.prepared)
            this.player.seekTo((int) (position * 1000));
        else
            this.shouldSeekTo = position;
    }

    public int getDuration() {
        return (int)this.player.getDuration();
    }

    public int getCurrentPosition() {
        return (int)this.player.getCurrentPosition();
    }

    public String getPlayerId() {
        return this.playerId;
    }

    public void setReleaseMode(ReleaseMode releaseMode) {
        if (this.releaseMode != releaseMode) {
            this.releaseMode = releaseMode;
            if (!this.released) {
                this.player.setRepeatMode(releaseMode == ReleaseMode.LOOP ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
            }
        }
    }

    public ReleaseMode getReleaseMode() {
        return this.releaseMode;
    }

    public void onPrepared(final SimpleExoPlayer player) {
        this.prepared = true;
        if (this.playing) {
            this.player.setPlayWhenReady(true);
            ref.handleIsPlaying(this);
        }
        if (this.shouldSeekTo >= 0) {
            this.player.seekTo((int) (this.shouldSeekTo * 1000));
            this.shouldSeekTo = -1;
        }
    }

    public void onCompletion(final SimpleExoPlayer player) {
        if (releaseMode != ReleaseMode.LOOP) {
            this.stop();
        }
        ref.handleCompletion(this);
    }

    @SuppressWarnings("deprecation")
    private void setAttributes(SimpleExoPlayer player) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    //.setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build()
            );
        } else {
            // This method is deprecated but must be used on older devices
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
    }

    public void setPlaybackParameters(double speed, boolean skipSilence) {
        this.player.setPlaybackParameters(new PlaybackParameters((float) speed, 1, skipSilence));
    }

    private SimpleExoPlayer createPlayer() {
        TrackSelector trackSelector = new DefaultTrackSelector();
        final SimpleExoPlayer exoPlayer = ExoPlayerFactory.newSimpleInstance(this.ref.registrar.context(), trackSelector, new DefaultLoadControl());
        exoPlayer.setVolume((float) volume);
        exoPlayer.setRepeatMode(this.releaseMode == ReleaseMode.LOOP ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
        setAttributes(exoPlayer);

        //player.setOnPreparedListener(this);
        //player.setOnCompletionListener(this);
        exoPlayer.addListener(
            new DefaultEventListener() {
                public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
                  super.onPlayerStateChanged(playWhenReady, playbackState);
                  if (playbackState == Player.STATE_READY && !prepared) {
                    onPrepared(exoPlayer);
                  }
                  else if (playbackState == Player.STATE_ENDED) {
                    onCompletion(exoPlayer);
                  }
                }

                public void onPlayerError(final ExoPlaybackException error) {
                  super.onPlayerError(error);
                }
            }
        );

        return exoPlayer;
    }

    private static boolean objectEquals(Object o1, Object o2) {
        return o1 == null && o2 == null || o1 != null && o1.equals(o2);
    }
}
