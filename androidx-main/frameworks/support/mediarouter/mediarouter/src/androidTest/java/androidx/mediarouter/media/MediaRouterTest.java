/*
* Copyright (C) 2017 The Android Open Source Project
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
* limitations under the License.
*/

package androidx.mediarouter.media;

import static androidx.mediarouter.media.MediaRouterActiveScanThrottlingHelper.MAX_ACTIVE_SCAN_DURATION_MS;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.mediarouter.testing.MediaRouterTestHelper;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test {@link MediaRouter}.
 */
@RunWith(AndroidJUnit4.class)
public class MediaRouterTest {
    // The maximum time to wait for an operation.
    private static final long TIME_OUT_MS = 3000L;
    private static final String SESSION_TAG = "test-session";

    private static final String TEST_KEY = "test_key";
    private static final String TEST_VALUE = "test_value";

    private final Object mWaitLock = new Object();

    private Context mContext;
    private MediaRouter mRouter;
    private MediaSessionCompat mSession;
    private MediaSessionCallback mSessionCallback = new MediaSessionCallback();
    private MediaRouteProviderImpl mProvider;
    private CountDownLatch mActiveScanCountDownLatch;
    private CountDownLatch mPassiveScanCountDownLatch;

    @Before
    public void setUp() {
        resetActiveAndPassiveScanCountDownLatches();
        getInstrumentation()
                .runOnMainSync(
                        () -> {
                            mContext = getApplicationContext();
                            mRouter = MediaRouter.getInstance(mContext);
                            mSession = new MediaSessionCompat(mContext, SESSION_TAG);
                            mProvider = new MediaRouteProviderImpl(mContext);
                        });
        assertTrue(MediaTransferReceiver.isDeclared(mContext));
    }

    @After
    public void tearDown() {
        mSession.release();
        getInstrumentation().runOnMainSync(() -> MediaRouterTestHelper.resetMediaRouter());
    }

    /**
     * This test checks whether the session callback work properly after setMediaSessionCompat() is
     * called.
     */
    @Test
    @SmallTest
    public void setMediaSessionCompat_receivesCallbacks() throws Exception {
        getInstrumentation()
                .runOnMainSync(
                        () -> {
                            mSession.setCallback(mSessionCallback);
                            mRouter.setMediaSessionCompat(mSession);
                        });

        MediaControllerCompat controller = mSession.getController();
        MediaControllerCompat.TransportControls controls = controller.getTransportControls();
        synchronized (mWaitLock) {
            mSessionCallback.reset();
            controls.play();
            mWaitLock.wait(TIME_OUT_MS);
            assertTrue(mSessionCallback.mOnPlayCalled);

            mSessionCallback.reset();
            controls.pause();
            mWaitLock.wait(TIME_OUT_MS);
            assertTrue(mSessionCallback.mOnPauseCalled);
        }
    }

    @Test
    @SmallTest
    @UiThreadTest
    public void getRouterParams_afterSetRouterParams_returnsSetParams() {
        final int dialogType = MediaRouterParams.DIALOG_TYPE_DYNAMIC_GROUP;
        final boolean isOutputSwitcherEnabled = true;
        final boolean transferToLocalEnabled = true;
        final boolean transferReceiverEnabled = false;
        final boolean mediaTransferRestrictedToSelfProviders = true;
        final Bundle paramExtras = new Bundle();
        paramExtras.putString(TEST_KEY, TEST_VALUE);

        MediaRouterParams expectedParams =
                new MediaRouterParams.Builder()
                        .setDialogType(dialogType)
                        .setOutputSwitcherEnabled(isOutputSwitcherEnabled)
                        .setTransferToLocalEnabled(transferToLocalEnabled)
                        .setMediaTransferReceiverEnabled(transferReceiverEnabled)
                        .setMediaTransferRestrictedToSelfProviders(
                                mediaTransferRestrictedToSelfProviders)
                        .setExtras(paramExtras)
                        .build();

        paramExtras.remove(TEST_KEY);
        mRouter.setRouterParams(expectedParams);

        MediaRouterParams actualParams = mRouter.getRouterParams();
        assertEquals(expectedParams, actualParams);
    }

    @SmallTest
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.R)
    public void setRouterParams_shouldSetMediaTransferRestrictToSelfProviders() {
        MediaRouterParams params =
                new MediaRouterParams.Builder()
                        .setMediaTransferRestrictedToSelfProviders(true)
                        .build();
        getInstrumentation()
                .runOnMainSync(
                        () -> {
                            mRouter.setRouterParams(params);
                        });
        assertTrue(
                MediaRouter.getGlobalRouter()
                        .mRegisteredProviderWatcher
                        .isMediaTransferRestrictedToSelfProvidersForTesting());
        assertTrue(
                MediaRouter.getGlobalRouter()
                        .getMediaRoute2ProviderForTesting()
                        .isMediaTransferRestrictedToSelfProviders());
    }

    @Test
    @LargeTest
    public void testRegisterActiveScanCallback_suppressActiveScanAfter30Seconds() throws Exception {
        MediaRouteSelector selector =
                new MediaRouteSelector.Builder()
                        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO).build();
        MediaRouterCallbackImpl callback = new MediaRouterCallbackImpl();

        // Add the provider and callback.
        resetActiveAndPassiveScanCountDownLatches();
        getInstrumentation()
                .runOnMainSync(
                        () -> {
                            mRouter.addProvider(mProvider);
                            mRouter.addCallback(
                                    selector,
                                    callback,
                                    MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
                        });

        // Active scan should be true.
        assertTrue(mActiveScanCountDownLatch.await(TIME_OUT_MS, TimeUnit.MILLISECONDS));

        // After active scan duration, active scan should be false.
        resetActiveAndPassiveScanCountDownLatches();
        assertTrue(mPassiveScanCountDownLatch.await(
                MAX_ACTIVE_SCAN_DURATION_MS + TIME_OUT_MS, TimeUnit.MILLISECONDS));

        // Add the same callback again.
        resetActiveAndPassiveScanCountDownLatches();
        getInstrumentation()
                .runOnMainSync(
                        () ->
                                mRouter.addCallback(
                                        selector,
                                        callback,
                                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN));

        // Active scan should be true.
        assertTrue(mActiveScanCountDownLatch.await(TIME_OUT_MS, TimeUnit.MILLISECONDS));

        // After active scan duration, active scan should be false.
        resetActiveAndPassiveScanCountDownLatches();
        assertTrue(mPassiveScanCountDownLatch.await(
                MAX_ACTIVE_SCAN_DURATION_MS + TIME_OUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    @LargeTest
    public void testRegisterMultipleActiveScanCallbacks_suppressActiveScanAfter30Seconds()
            throws Exception {
        MediaRouteSelector selector =
                new MediaRouteSelector.Builder()
                        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO).build();
        MediaRouterCallbackImpl callback1 = new MediaRouterCallbackImpl();
        MediaRouterCallbackImpl callback2 = new MediaRouterCallbackImpl();

        // Add the provider and the first callback.
        getInstrumentation()
                .runOnMainSync(
                        () -> {
                            mRouter.addProvider(mProvider);
                            mRouter.addCallback(
                                    selector,
                                    callback1,
                                    MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
                        });

        // Wait for 5 seconds, add the second callback.
        Thread.sleep(5000);
        getInstrumentation()
                .runOnMainSync(
                        () ->
                                mRouter.addCallback(
                                        selector,
                                        callback2,
                                        MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN));

        resetActiveAndPassiveScanCountDownLatches();
        // Wait for active scan duration to nearly end, active scan flag should be true.
        assertFalse(mPassiveScanCountDownLatch.await(MAX_ACTIVE_SCAN_DURATION_MS - 1000,
                TimeUnit.MILLISECONDS));

        // Wait until active scan duration ends, active scan flag should be false.
        assertTrue(mPassiveScanCountDownLatch.await(1000 + TIME_OUT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    @UiThreadTest
    public void testReset() {
        assertNotNull(mRouter);
        assertNotNull(MediaRouter.sGlobal);

        MediaRouterTestHelper.resetMediaRouter();
        assertNull(MediaRouter.sGlobal);

        MediaRouter newInstance = MediaRouter.getInstance(mContext);
        assertNotNull(MediaRouter.sGlobal);
        assertFalse(newInstance.getRoutes().isEmpty());
    }

    private class MediaSessionCallback extends MediaSessionCompat.Callback {
        private boolean mOnPlayCalled;
        private boolean mOnPauseCalled;

        public void reset() {
            mOnPlayCalled = false;
            mOnPauseCalled = false;
        }

        @Override
        public void onPlay() {
            synchronized (mWaitLock) {
                mOnPlayCalled = true;
                mWaitLock.notify();
            }
        }

        @Override
        public void onPause() {
            synchronized (mWaitLock) {
                mOnPauseCalled = true;
                mWaitLock.notify();
            }
        }
    }

    private class MediaRouteProviderImpl extends MediaRouteProvider {
        private boolean mIsActiveScan;

        MediaRouteProviderImpl(Context context) {
            super(context);
        }

        @Override
        public void onDiscoveryRequestChanged(MediaRouteDiscoveryRequest discoveryRequest) {
            boolean isActiveScan = discoveryRequest != null && discoveryRequest.isActiveScan();
            if (mIsActiveScan != isActiveScan) {
                mIsActiveScan = isActiveScan;
                if (mIsActiveScan) {
                    mActiveScanCountDownLatch.countDown();
                } else {
                    mPassiveScanCountDownLatch.countDown();
                }
            }
        }
    }

    private void resetActiveAndPassiveScanCountDownLatches() {
        mActiveScanCountDownLatch = new CountDownLatch(1);
        mPassiveScanCountDownLatch = new CountDownLatch(1);
    }

    private static class MediaRouterCallbackImpl extends MediaRouter.Callback {}
}
