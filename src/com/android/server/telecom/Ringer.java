/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom;

import static android.provider.CallLog.Calls.USER_MISSED_DND_MODE;
import static android.provider.CallLog.Calls.USER_MISSED_LOW_RING_VOLUME;
import static android.provider.CallLog.Calls.USER_MISSED_NO_VIBRATE;
import static android.provider.Settings.Global.ZEN_MODE_OFF;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.VolumeShaper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecom.Log;
import android.telecom.TelecomManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.LogUtils.EventTimer;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Controls the ringtone player.
 */
@VisibleForTesting
public class Ringer {
    public static class VibrationEffectProxy {
        public VibrationEffect createOneShot(long milliseconds, int amplitude) {
            return VibrationEffect.createOneShot(milliseconds, amplitude);
        }

        public VibrationEffect createWaveform(long[] timings, int[] amplitudes, int repeat) {
            return VibrationEffect.createWaveform(timings, amplitudes, repeat);
        }

        public VibrationEffect get(Uri ringtoneUri, Context context) {
            return VibrationEffect.get(ringtoneUri, context);
        }
    }
    @VisibleForTesting
    public VibrationEffect mDefaultVibrationEffect;

    // Used for test to notify the completion of RingerAttributes
    private CountDownLatch mAttributesLatch;

    private static final long[] PULSE_PRIMING_PATTERN = {0,12,250,12,500}; // priming  + interval

    private static final int[] PULSE_PRIMING_AMPLITUDE = {0,255,0,255,0};  // priming  + interval

    // ease-in + peak + pause
    private static final long[] PULSE_RAMPING_PATTERN = {
        50,50,50,50,50,50,50,50,50,50,50,50,50,50,300,1000};

    // ease-in (min amplitude = 30%) + peak + pause
    private static final int[] PULSE_RAMPING_AMPLITUDE = {
        77,77,78,79,81,84,87,93,101,114,133,162,205,255,255,0};

    private static final long[] PULSE_PATTERN;

    private static final int[] PULSE_AMPLITUDE;

    private static final int RAMPING_RINGER_VIBRATION_DURATION = 5000;
    private static final int RAMPING_RINGER_DURATION = 10000;

    private static final int OUTGOING_CALL_VIBRATING_DURATION = 100;

    static {
        // construct complete pulse pattern
        PULSE_PATTERN = new long[PULSE_PRIMING_PATTERN.length + PULSE_RAMPING_PATTERN.length];
        System.arraycopy(
            PULSE_PRIMING_PATTERN, 0, PULSE_PATTERN, 0, PULSE_PRIMING_PATTERN.length);
        System.arraycopy(PULSE_RAMPING_PATTERN, 0, PULSE_PATTERN,
            PULSE_PRIMING_PATTERN.length, PULSE_RAMPING_PATTERN.length);

        // construct complete pulse amplitude
        PULSE_AMPLITUDE = new int[PULSE_PRIMING_AMPLITUDE.length + PULSE_RAMPING_AMPLITUDE.length];
        System.arraycopy(
            PULSE_PRIMING_AMPLITUDE, 0, PULSE_AMPLITUDE, 0, PULSE_PRIMING_AMPLITUDE.length);
        System.arraycopy(PULSE_RAMPING_AMPLITUDE, 0, PULSE_AMPLITUDE,
            PULSE_PRIMING_AMPLITUDE.length, PULSE_RAMPING_AMPLITUDE.length);
    }

    private static final long[] SIMPLE_VIBRATION_PATTERN = {
        0, // No delay before starting
        1000, // How long to vibrate
        1000, // How long to wait before vibrating again
        1000, // How long to vibrate
        1000, // How long to wait before vibrating again
    };

    private static final long[] DZZZ_DA_VIBRATION_PATTERN = {
        0, // No delay before starting
        500, // How long to vibrate
        200, // Delay
        70, // How long to vibrate
        720, // How long to wait before vibrating again
    };

    private static final long[] MM_MM_MM_VIBRATION_PATTERN = {
        0, // No delay before starting
        300, // How long to vibrate
        400, // Delay
        300, // How long to vibrate
        400, // Delay
        300, // How long to vibrate
        1400, // How long to wait before vibrating again
    };

    private static final long[] DA_DA_DZZZ_VIBRATION_PATTERN = {
        0, // No delay before starting
        70, // How long to vibrate
        80, // Delay
        70, // How long to vibrate
        180, // Delay
        600,  // How long to vibrate
        1050, // How long to wait before vibrating again
    };

    private static final long[] DA_DZZZ_DA_VIBRATION_PATTERN = {
        0, // No delay before starting
        80, // How long to vibrate
        200, // Delay
        600, // How long to vibrate
        150, // Delay
        60,  // How long to vibrate
        1050, // How long to wait before vibrating again
    };

    private static final int[] SEVEN_ELEMENTS_VIBRATION_AMPLITUDE = {
        0, // No delay before starting
        255, // Vibrate full amplitude
        0, // No amplitude while waiting
        255,
        0,
        255,
        0,
    };

    private static final int[] FIVE_ELEMENTS_VIBRATION_AMPLITUDE = {
        0, // No delay before starting
        255, // Vibrate full amplitude
        0, // No amplitude while waiting
        255,
        0,
    };

    private boolean mUseSimplePattern;
    private int mVibrationPattern;
    private SettingsObserver mSettingObserver;
    private final Handler mH = new Handler();

    /**
     * Indicates that vibration should be repeated at element 5 in the {@link #PULSE_AMPLITUDE} and
     * {@link #PULSE_PATTERN} arrays.  This means repetition will happen for the main ease-in/peak
     * pattern, but the priming + interval part will not be repeated.
     */
    private static final int REPEAT_VIBRATION_AT = 5;

    private static final int REPEAT_SIMPLE_VIBRATION_AT = 1;

    private static final long RINGER_ATTRIBUTES_TIMEOUT = 5000; // 5 seconds

    private static final float EPSILON = 1e-6f;

    private static final VibrationAttributes VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_RINGTONE).build();

    private static VolumeShaper.Configuration mVolumeShaperConfig;

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */

    private final SystemSettingsUtil mSystemSettingsUtil;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final AsyncRingtonePlayer mRingtonePlayer;
    private final Context mContext;
    private final Vibrator mVibrator;
    private final InCallController mInCallController;
    private final VibrationEffectProxy mVibrationEffectProxy;
    private final boolean mIsHapticPlaybackSupportedByDevice;
    /**
     * For unit testing purposes only; when set, {@link #startRinging(Call, boolean)} will complete
     * the future provided by the test using {@link #setBlockOnRingingFuture(CompletableFuture)}.
     */
    private CompletableFuture<Void> mBlockOnRingingFuture = null;

    private CompletableFuture<Void> mVibrateFuture = CompletableFuture.completedFuture(null);

    private InCallTonePlayer mCallWaitingPlayer;
    private RingtoneFactory mRingtoneFactory;
    private AudioManager mAudioManager;

    /**
     * Call objects that are ringing, vibrating or call-waiting. These are used only for logging
     * purposes.
     */
    private Call mRingingCall;
    private Call mVibratingCall;
    private Call mCallWaitingCall;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private volatile boolean mIsVibrating = false;

    private Handler mHandler = null;
    private int mSavedSpeakerInCallVolume = -1;
    private CommunicationDeviceChangedListener mCommunicationDeviceChangedListener = null;

    /**
     * Use lock different from the Telecom sync because ringing process is asynchronous outside that
     * lock
     */
    private final Object mLock;

    /** Initializes the Ringer. */
    @VisibleForTesting
    public Ringer(
            InCallTonePlayer.Factory playerFactory,
            Context context,
            SystemSettingsUtil systemSettingsUtil,
            AsyncRingtonePlayer asyncRingtonePlayer,
            RingtoneFactory ringtoneFactory,
            Vibrator vibrator,
            VibrationEffectProxy vibrationEffectProxy,
            InCallController inCallController) {

        mLock = new Object();
        mSystemSettingsUtil = systemSettingsUtil;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = vibrator;
        mRingtonePlayer = asyncRingtonePlayer;
        mRingtoneFactory = ringtoneFactory;
        mInCallController = inCallController;
        mVibrationEffectProxy = vibrationEffectProxy;
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mUseSimplePattern = mContext.getResources().getBoolean(R.bool.use_simple_vibration_pattern);
        mVibrationPattern = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.RINGTONE_VIBRATION_PATTERN, 0, UserHandle.USER_CURRENT);

        updateVibrationPattern();

        mSettingObserver = new SettingsObserver(mH);
        mContext.getContentResolver().registerContentObserver(
            Settings.System.getUriFor(Settings.System.RINGTONE_VIBRATION_PATTERN),
            true, mSettingObserver, UserHandle.USER_CURRENT);

        mIsHapticPlaybackSupportedByDevice =
                mSystemSettingsUtil.isHapticPlaybackSupported(mContext);
    }

    @VisibleForTesting
    public void setBlockOnRingingFuture(CompletableFuture<Void> future) {
        mBlockOnRingingFuture = future;
    }

    public boolean startPlayingCrs(Call foregroundCall, boolean isHfpDeviceAttached) {
        if (foregroundCall == null) {
            Log.wtf(this, "startPlayingCrs called with null foreground call.");
            return false;
        }

        boolean isCrsCall = foregroundCall.isCrsCall();
        Log.i(this, "startPlayingCrs called with video CRS is :: " + isCrsCall);
        if (!isCrsCall) {
            return false;
        }

        if (foregroundCall.getState() != CallState.RINGING
                && foregroundCall.getState() != CallState.SIMULATED_RINGING) {
            // Its possible for bluetooth to connect JUST as a call goes active, which would mean
            // the call would start ringing again.
            Log.i(this, "startRinging called for non-ringing foreground callid=%s",
                    foregroundCall.getId());
            return false;
        }

        LogUtils.EventTimer timer = new EventTimer();
        boolean isVolumeOverZero = mAudioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;
        timer.record("isVolumeOverZero");
        boolean shouldRingForContact = shouldRingForContact(foregroundCall.getContactUri());
        timer.record("shouldRingForContact");
        boolean isSelfManaged = foregroundCall.isSelfManaged();
        timer.record("isSelfManaged");
        boolean isSilentRingingRequested = foregroundCall.isSilentRingingRequested();
        timer.record("isSilentRingRequested");

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact && isCrsCall;
        timer.record("isRingerAudible");
        boolean hasExternalRinger = hasExternalRinger(foregroundCall);
        timer.record("hasExternalRinger");
        // Don't do call waiting operations or vibration unless these are false.
        boolean isTheaterModeOn = mSystemSettingsUtil.isTheaterModeOn(mContext);
        timer.record("isTheaterModeOn");
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging();
        timer.record("letDialerHandleRinging");

        Log.i(this, "startRinging timings: " + timer);
        boolean endEarly = isTheaterModeOn || letDialerHandleRinging || isSelfManaged ||
                hasExternalRinger || isSilentRingingRequested;

        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present, here is CRS from network.
        // 3. The call is self-managed.
        boolean shouldAcquireAudioFocus =
                isRingerAudible || (isHfpDeviceAttached && shouldRingForContact) || isSelfManaged;

        if (endEarly) {
            if (letDialerHandleRinging) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            }
            if (isSilentRingingRequested) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Silent ringing "
                        + "requested");
            }
            Log.i(this, "Ending early -- isTheaterModeOn=%s, letDialerHandleRinging=%s, " +
                            "isSelfManaged=%s, hasExternalRinger=%s, silentRingingRequested=%s",
                    isTheaterModeOn, letDialerHandleRinging, isSelfManaged, hasExternalRinger,
                    isSilentRingingRequested);
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            return shouldAcquireAudioFocus;
        }

        stopCallWaiting();
        VibrationEffect effect;
        // Determine if the settings and DND mode indicate that the vibrator can be used right now.
        boolean isVibratorEnabled = isVibratorEnabled(mContext, isRingerAudible);
        if (isRingerAudible) {
            mRingingCall = foregroundCall;
            Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);
            // Because we wait until a contact info query to complete before processing a
            // call (for the purposes of direct-to-voicemail), the information about custom
            // ringtones should be available by the time this code executes. We can safely
            // request the custom ringtone from the call and expect it to be current.
            boolean shouldApplyRampingRinger =
                    isVibratorEnabled && mSystemSettingsUtil.isRampingRingerEnabled(mContext);
            if (shouldApplyRampingRinger) {
                Log.i(this, "start ramping ringer.");
                if (mSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled()) {
                    effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
                } else {
                    effect = mDefaultVibrationEffect;
                }
            } else {
                effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
            }
        } else {
            String reason = String.format(
                    "isVolumeOverZero=%s, shouldRingForContact=%s, isCrsCall=%s",
                    isVolumeOverZero, shouldRingForContact, isCrsCall);
            Log.i(this, "startRinging: skipping because ringer would not be audible. " + reason);
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Inaudible: " + reason);
            effect = mDefaultVibrationEffect;
        }

        Log.i(this, "isHfpDeviceAttached=%s, isVibratorEnabled=%s, isRingerAudible=%s, ",
                isHfpDeviceAttached, isVibratorEnabled, isRingerAudible);
        if (isRingerAudible) {
            if (!mAudioManager.isSpeakerphoneOn()) {
                mCommunicationDeviceChangedListener = new CommunicationDeviceChangedListener();
                try {
                    mAudioManager.addOnCommunicationDeviceChangedListener(
                            mContext.getMainExecutor(), mCommunicationDeviceChangedListener);
                } catch (Exception e) {
                    Log.i(this, "addOnCommunicationDeviceChangedListener failed with exception: "
                            + e);
                }
            } else {
                Log.i(this,"Speaker is ON for CRS.");
                setSystemSystemSpeakerInCallVolume();
            }
        }
        if (mBlockOnRingingFuture != null) {
            mBlockOnRingingFuture.complete(null);
        }
        maybeStartVibration(foregroundCall, shouldRingForContact,
                effect, isVibratorEnabled, isRingerAudible);

        return shouldAcquireAudioFocus;
    }

    class CommunicationDeviceChangedListener implements
        AudioManager.OnCommunicationDeviceChangedListener {
            @Override
            public void onCommunicationDeviceChanged(AudioDeviceInfo device) {
                if (device == null) {
                    return;
                }
                Log.i(this,"onCommunicationDeviceChanged, Device type : "
                        + device.getInternalType());
                if (device.getInternalType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    setSystemSystemSpeakerInCallVolume();
                }
            }
     }

    private void setSystemSystemSpeakerInCallVolume() {
        int ringVolumeLevel = mAudioManager.getStreamVolume(AudioManager.STREAM_RING);
        if (ringVolumeLevel > 0) {
            Log.i(this, "Start play CRS with volume :: " + ringVolumeLevel);
            // Set the CRS volume with local ring volume  and save the old volume setting.
            mSavedSpeakerInCallVolume = mAudioManager.getStreamVolume(
                    AudioManager.STREAM_VOICE_CALL);
            Log.i(this, "mSavedSpeakerInCallVolume is :: " + mSavedSpeakerInCallVolume);
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    convertVolumeLevelFromRingToCrs(ringVolumeLevel), 0);
        }
    }

    public void restoreSystemSpeakerInCallVolume() {
        boolean speakerOn = mAudioManager.isSpeakerphoneOn();
        Log.i(this, "restoreSystemSpeakerInCallVolume :: speaker ON =  " + speakerOn
                + ", mSavedSpeakerInCallVolume = " + mSavedSpeakerInCallVolume);
        muteCrs(false);
        if (speakerOn && (mSavedSpeakerInCallVolume != -1)) {
            // Restore inCall volume after getting ACTIVE/DISCONNECTED state as
            // CRS volume used the system ringing volume level.
            // And set volume level for speaker only.
            mAudioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,
                    mSavedSpeakerInCallVolume, 0);
            mSavedSpeakerInCallVolume = -1;
            Log.i(this, "restoreSystemSpeakerInCallVolume done");
        }
    }

    private int convertVolumeLevelFromRingToCrs(int ringVolume) {
        // CRS volume is same as voice call volume per design and telephony should
        // adjust voice volume according to ring volume when playing CRS audio,
        // however the range of local ring volume and voice call volume are different
        // for different devices, telephony needs to align volume level between local
        // ring and CRS(voice call volume) according to device audio configuration.
        final int maxVoiceCallVolume = mAudioManager.getStreamMaxVolume(
                AudioManager.STREAM_VOICE_CALL);
        final int maxRingVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
        final int minVoiceCallVolume = mAudioManager.getStreamMinVolume(
                AudioManager.STREAM_VOICE_CALL);
        final int minRingVolume = mAudioManager.getStreamMinVolume(AudioManager.STREAM_RING);
        if (ringVolume >= maxRingVolume) {
            return maxVoiceCallVolume;
        }
        final float ratio =(float) (maxVoiceCallVolume - minVoiceCallVolume) /
            (maxRingVolume - minRingVolume);
        int crsVolume = minVoiceCallVolume + (int)Math.round(ratio * (ringVolume - minRingVolume));
        if (crsVolume >= maxVoiceCallVolume) {
            crsVolume = maxVoiceCallVolume;
        }
        Log.i(this, "maxVoiceCallVol=%d, maxRingVol=%d, minVoiceCallVol=%d, "
                + "minRingVol=%d, crsVolume=%d, ",
                maxVoiceCallVolume,
                maxRingVolume,
                minVoiceCallVolume,
                minRingVolume,
                crsVolume);
        return crsVolume;
    }

    public boolean startRinging(Call foregroundCall, boolean isHfpDeviceAttached) {
        if (foregroundCall == null) {
            Log.wtf(this, "startRinging called with null foreground call.");
            return false;
        }

        if (foregroundCall.getState() != CallState.RINGING
                && foregroundCall.getState() != CallState.SIMULATED_RINGING) {
            // Its possible for bluetooth to connect JUST as a call goes active, which would mean
            // the call would start ringing again.
            Log.i(this, "startRinging called for non-ringing foreground callid=%s",
                    foregroundCall.getId());
            return false;
        }

        // Use completable future to establish a timeout, not intent to make these work outside the
        // main thread asynchronously
        // TODO: moving these RingerAttributes calculation out of Telecom lock to avoid blocking.
        CompletableFuture<RingerAttributes> ringerAttributesFuture = CompletableFuture
                .supplyAsync(() -> getRingerAttributes(foregroundCall, isHfpDeviceAttached),
                        new LoggedHandlerExecutor(getHandler(), "R.sR", null));

        RingerAttributes attributes = null;
        try {
            mAttributesLatch = new CountDownLatch(1);
            attributes = ringerAttributesFuture.get(
                    RINGER_ATTRIBUTES_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // Keep attributs as null
            Log.i(this, "getAttributes error: " + e);
        }

        if (attributes == null) {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "RingerAttributes error");
            return false;
        }

        if (attributes.isEndEarly()) {
            if (attributes.letDialerHandleRinging()) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            }
            if (attributes.isSilentRingingRequested()) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Silent ringing "
                        + "requested");
            }
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            return attributes.shouldAcquireAudioFocus();
        }

        stopCallWaiting();

        VibrationEffect effect;
        CompletableFuture<Boolean> hapticsFuture = null;
        // Determine if the settings and DND mode indicate that the vibrator can be used right now.
        boolean isVibratorEnabled = isVibratorEnabled(mContext, attributes.shouldRingForContact());
        boolean shouldApplyRampingRinger =
                isVibratorEnabled && mSystemSettingsUtil.isRampingRingerEnabled(mContext);
        if (attributes.isRingerAudible()) {
            mRingingCall = foregroundCall;
            Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);
            // Because we wait until a contact info query to complete before processing a
            // call (for the purposes of direct-to-voicemail), the information about custom
            // ringtones should be available by the time this code executes. We can safely
            // request the custom ringtone from the call and expect it to be current.
            if (shouldApplyRampingRinger) {
                Log.i(this, "start ramping ringer.");
                if (mSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled()) {
                    effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
                } else {
                    effect = mDefaultVibrationEffect;
                }
                if (mVolumeShaperConfig == null) {
                    float silencePoint = (float) (RAMPING_RINGER_VIBRATION_DURATION)
                            / (float) (RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION);
                    mVolumeShaperConfig = new VolumeShaper.Configuration.Builder()
                            .setDuration(
                                    RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION)
                            .setCurve(new float[]{0.f, silencePoint + EPSILON /*keep monotonicity*/,
                                    1.f}, new float[]{0.f, 0.f, 1.f})
                            .setInterpolatorType(
                                    VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                            .build();
                }
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall,
                        mVolumeShaperConfig, attributes.isRingerAudible(), isVibratorEnabled);
            } else {
                // Ramping ringtone is not enabled.
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall, null,
                        attributes.isRingerAudible(), isVibratorEnabled);
                effect = getVibrationEffectForCall(mRingtoneFactory, foregroundCall);
            }
        } else {
            Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Inaudible: "
                    + attributes.getInaudibleReason());
            if (isVibratorEnabled && mIsHapticPlaybackSupportedByDevice) {
                // Attempt to run the attentional haptic ringtone first and fallback to the default
                // vibration effect if hapticFuture is completed with false.
                hapticsFuture = mRingtonePlayer.play(mRingtoneFactory, foregroundCall, null,
                        attributes.isRingerAudible(), isVibratorEnabled);
            }
            effect = mDefaultVibrationEffect;
        }

        if (hapticsFuture != null) {
            final boolean shouldRingForContact = attributes.shouldRingForContact();
            final boolean isRingerAudible = attributes.isRingerAudible();
            mVibrateFuture = hapticsFuture.thenAccept(isUsingAudioCoupledHaptics -> {
                if (!isUsingAudioCoupledHaptics || !mIsHapticPlaybackSupportedByDevice) {
                    Log.i(this, "startRinging: fileHasHaptics=%b, hapticsSupported=%b",
                            isUsingAudioCoupledHaptics, mIsHapticPlaybackSupportedByDevice);
                    maybeStartVibration(foregroundCall, shouldRingForContact, effect,
                            isVibratorEnabled, isRingerAudible);
                } else if (shouldApplyRampingRinger
                        && !mSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled()) {
                    Log.i(this, "startRinging: apply ramping ringer vibration");
                    maybeStartVibration(foregroundCall, shouldRingForContact, effect,
                            isVibratorEnabled, isRingerAudible);
                } else {
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION,
                            "using audio-coupled haptics");
                }
            });
            if (mBlockOnRingingFuture != null) {
                mVibrateFuture.whenComplete((v, e) -> mBlockOnRingingFuture.complete(null));
            }
        } else {
            if (mBlockOnRingingFuture != null) {
                mBlockOnRingingFuture.complete(null);
            }
            Log.w(this, "startRinging: No haptics future; fallback to default behavior");
            maybeStartVibration(foregroundCall, attributes.shouldRingForContact(), effect,
                    isVibratorEnabled, attributes.isRingerAudible());
        }

        return attributes.shouldAcquireAudioFocus();
    }

    private void maybeStartVibration(Call foregroundCall, boolean shouldRingForContact,
        VibrationEffect effect, boolean isVibrationEnabled, boolean isRingerAudible) {
        synchronized (mLock) {
            mAudioManager = mContext.getSystemService(AudioManager.class);
            if (isVibrationEnabled && !mIsVibrating && shouldRingForContact) {
                Log.addEvent(foregroundCall, LogUtils.Events.START_VIBRATOR,
                        "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                        mVibrator.hasVibrator(),
                        mSystemSettingsUtil.isRingVibrationEnabled(mContext),
                        mAudioManager.getRingerMode(), mIsVibrating);
                if (mSystemSettingsUtil.isRampingRingerEnabled(mContext) && isRingerAudible) {
                    Log.i(this, "start vibration for ramping ringer.");
                } else {
                    Log.i(this, "start normal vibration.");
                }
                mIsVibrating = true;
                mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
            } else {
                foregroundCall.setUserMissed(USER_MISSED_NO_VIBRATE);
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION,
                        "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                        mVibrator.hasVibrator(),
                        mSystemSettingsUtil.isRingVibrationEnabled(mContext),
                        mAudioManager.getRingerMode(), mIsVibrating);
            }
        }
    }

    private VibrationEffect getVibrationEffectForCall(RingtoneFactory factory, Call call) {
        VibrationEffect effect = null;
        Ringtone ringtone = factory.getRingtone(call);
        Uri ringtoneUri = ringtone != null ? ringtone.getUri() : null;
        if (ringtoneUri != null) {
            try {
                effect = mVibrationEffectProxy.get(ringtoneUri, mContext);
            } catch (IllegalArgumentException iae) {
                // Deep in the bowels of the VibrationEffect class it is possible for an
                // IllegalArgumentException to be thrown if there is an invalid URI specified in the
                // device config, or a content provider failure.  Rather than crashing the Telecom
                // process we will just use the default vibration effect.
                Log.e(this, iae, "getVibrationEffectForCall: failed to get vibration effect");
                effect = null;
            }
        }

        if (effect == null) {
            effect = mDefaultVibrationEffect;
        }
        return effect;
    }

    public void startCallWaiting(Call call) {
        startCallWaiting(call, null);
    }

    public void startCallWaiting(Call call, String reason) {
        if (mSystemSettingsUtil.isTheaterModeOn(mContext)) {
            return;
        }

        if (mInCallController.doesConnectedDialerSupportRinging()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            return;
        }

        if (call.isSelfManaged()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Self-managed");
            return;
        }

        Log.v(this, "Playing call-waiting tone.");

        stopRinging();

        if (mCallWaitingPlayer == null) {
            Log.addEvent(call, LogUtils.Events.START_CALL_WAITING_TONE, reason);
            mCallWaitingCall = call;
            mCallWaitingPlayer =
                    mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
            mCallWaitingPlayer.startTone();
        }
    }

    public void muteCrs(boolean mute) {
        Log.i(this, "Mute CRS : " + mute);
        mAudioManager.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL,
                mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
    }

    public void stopPlayingCrs() {
        if (mRingingCall != null) {
            Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
            mRingingCall = null;
        }

        if (mCommunicationDeviceChangedListener != null) {
            try {
                mAudioManager.removeOnCommunicationDeviceChangedListener(
                        mCommunicationDeviceChangedListener);
            } catch (Exception e) {
                Log.i(this, "removeOnCommunicationDeviceChangedListener failed with exception: "
                         + e);
            }
            mCommunicationDeviceChangedListener = null;

        }
        // If we haven't started vibrating because we were waiting for the haptics info, cancel
        // it and don't vibrate at all.
        if (mVibrateFuture != null) {
            mVibrateFuture.cancel(true);
        }

        if (mIsVibrating) {
            Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
            mVibrator.cancel();
            mIsVibrating = false;
            mVibratingCall = null;
        }
    }

    public void stopRinging() {
        synchronized (mLock) {
            if (mRingingCall != null) {
                Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
                mRingingCall = null;
            }

            mRingtonePlayer.stop();

            // If we haven't started vibrating because we were waiting for the haptics info, cancel
            // it and don't vibrate at all.
            if (mVibrateFuture != null) {
                mVibrateFuture.cancel(true);
            }

            if (mIsVibrating) {
                Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
                mVibrator.cancel();
                mIsVibrating = false;
                mVibratingCall = null;
            }
        }
    }

    public void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            if (mCallWaitingCall != null) {
                Log.addEvent(mCallWaitingCall, LogUtils.Events.STOP_CALL_WAITING_TONE);
                mCallWaitingCall = null;
            }

            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    public boolean isRinging() {
        return mRingtonePlayer.isPlaying();
    }

    private boolean shouldRingForContact(Uri contactUri) {
        final NotificationManager manager =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        final Bundle peopleExtras = new Bundle();
        if (contactUri != null) {
            ArrayList<Person> personList = new ArrayList<>();
            personList.add(new Person.Builder().setUri(contactUri.toString()).build());
            peopleExtras.putParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, personList);
        }
        return manager.matchesCallFilter(peopleExtras);
    }

    private boolean hasExternalRinger(Call foregroundCall) {
        Bundle intentExtras = foregroundCall.getIntentExtras();
        if (intentExtras != null) {
            return intentExtras.getBoolean(TelecomManager.EXTRA_CALL_HAS_IN_BAND_RINGTONE, false);
        } else {
            return false;
        }
    }

    private boolean isVibratorEnabled(Context context, boolean shouldRingForContact) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // Use AudioManager#getRingerMode for more accurate result, instead of
        // AudioManager#getRingerModeInternal which only useful for volume controllers
        NotificationManager notificationManager = context.getSystemService(
                NotificationManager.class);
        boolean zenModeOn = notificationManager != null
                && notificationManager.getZenMode() != ZEN_MODE_OFF;
        return mVibrator.hasVibrator()
                && mSystemSettingsUtil.isRingVibrationEnabled(context)
                && (audioManager.getRingerMode() != AudioManager.RINGER_MODE_SILENT
                || (zenModeOn && shouldRingForContact));
    }

    public void startVibratingForOutgoingCallActive() {
        if (!mIsVibrating
                && Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.VIBRATING_FOR_OUTGOING_CALL_ACCEPTED, 1) == 1) {
            mIsVibrating = true;
            java.util.concurrent.Executors.defaultThreadFactory().newThread(() -> {
                final VibrationEffect vibrationEffect =
                        mVibrationEffectProxy.createOneShot(
                            OUTGOING_CALL_VIBRATING_DURATION, 255);
                final AudioAttributes vibrationAttributes = new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build();
                mVibrator.vibrate(vibrationEffect, vibrationAttributes);
                mIsVibrating = false;
            }).start();
        }
    }

    private RingerAttributes getRingerAttributes(Call call, boolean isHfpDeviceAttached) {
        mAudioManager = mContext.getSystemService(AudioManager.class);
        RingerAttributes.Builder builder = new RingerAttributes.Builder();

        LogUtils.EventTimer timer = new EventTimer();

        boolean isVolumeOverZero = mAudioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;
        timer.record("isVolumeOverZero");
        boolean shouldRingForContact = shouldRingForContact(call.getHandle());
        timer.record("shouldRingForContact");
        boolean isRingtonePresent = !(mRingtoneFactory.getRingtone(call) == null);
        timer.record("getRingtone");
        boolean isSelfManaged = call.isSelfManaged();
        timer.record("isSelfManaged");
        boolean isSilentRingingRequested = call.isSilentRingingRequested();
        timer.record("isSilentRingRequested");

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact && isRingtonePresent;
        timer.record("isRingerAudible");
        String inaudibleReason = "";
        if (!isRingerAudible) {
            inaudibleReason = String.format(
                    "isVolumeOverZero=%s, shouldRingForContact=%s, isRingtonePresent=%s",
                    isVolumeOverZero, shouldRingForContact, isRingtonePresent);
        }

        boolean hasExternalRinger = hasExternalRinger(call);
        timer.record("hasExternalRinger");
        // Don't do call waiting operations or vibration unless these are false.
        boolean isTheaterModeOn = mSystemSettingsUtil.isTheaterModeOn(mContext);
        timer.record("isTheaterModeOn");
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging();
        timer.record("letDialerHandleRinging");

        Log.i(this, "startRinging timings: " + timer);
        boolean endEarly = isTheaterModeOn || letDialerHandleRinging || isSelfManaged ||
                hasExternalRinger || isSilentRingingRequested;

        if (endEarly) {
            Log.i(this, "Ending early -- isTheaterModeOn=%s, letDialerHandleRinging=%s, " +
                            "isSelfManaged=%s, hasExternalRinger=%s, silentRingingRequested=%s",
                    isTheaterModeOn, letDialerHandleRinging, isSelfManaged, hasExternalRinger,
                    isSilentRingingRequested);
        }

        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present.
        // 3. The call is self-managed.
        boolean shouldAcquireAudioFocus =
                isRingerAudible || (isHfpDeviceAttached && shouldRingForContact) || isSelfManaged;

        // Set missed reason according to attributes
        if (!isVolumeOverZero) {
            call.setUserMissed(USER_MISSED_LOW_RING_VOLUME);
        }
        if (!shouldRingForContact) {
            call.setUserMissed(USER_MISSED_DND_MODE);
        }

        mAttributesLatch.countDown();
        return builder.setEndEarly(endEarly)
                .setLetDialerHandleRinging(letDialerHandleRinging)
                .setAcquireAudioFocus(shouldAcquireAudioFocus)
                .setRingerAudible(isRingerAudible)
                .setInaudibleReason(inaudibleReason)
                .setShouldRingForContact(shouldRingForContact)
                .setSilentRingingRequested(isSilentRingingRequested)
                .build();
    }

    private Handler getHandler() {
        if (mHandler == null) {
            HandlerThread handlerThread = new HandlerThread("Ringer");
            handlerThread.start();
            mHandler = handlerThread.getThreadHandler();
        }
        return mHandler;
    }

    @VisibleForTesting
    public boolean waitForAttributesCompletion() throws InterruptedException {
        if (mAttributesLatch != null) {
            return mAttributesLatch.await(RINGER_ATTRIBUTES_TIMEOUT, TimeUnit.MILLISECONDS);
        } else {
            return false;
        }
    }

    private void updateVibrationPattern() {
        mVibrationPattern = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.RINGTONE_VIBRATION_PATTERN, 0, UserHandle.USER_CURRENT);
        if (mUseSimplePattern) {
            switch (mVibrationPattern) {
                case 1:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(DZZZ_DA_VIBRATION_PATTERN,
                        FIVE_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 2:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(MM_MM_MM_VIBRATION_PATTERN,
                        SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 3:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(DA_DA_DZZZ_VIBRATION_PATTERN,
                        SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                case 4:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(DA_DZZZ_DA_VIBRATION_PATTERN,
                        SEVEN_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
                default:
                    mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(SIMPLE_VIBRATION_PATTERN,
                        FIVE_ELEMENTS_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
                    break;
            }
        } else {
            mDefaultVibrationEffect = mVibrationEffectProxy.createWaveform(PULSE_PATTERN,
                    PULSE_AMPLITUDE, REPEAT_VIBRATION_AT);
        }
    }

    private final class SettingsObserver extends ContentObserver {
        public SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean SelfChange) {
            updateVibrationPattern();
        }
    }
}
