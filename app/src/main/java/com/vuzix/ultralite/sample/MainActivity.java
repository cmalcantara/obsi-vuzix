package com.vuzix.ultralite.sample;

import java.util.*;

// AndroidX and Material Design Imports
import android.app.Application;
import android.content.Context;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

// Vuzix Ultralite SDK Imports
import com.vuzix.ultralite.LVGLImage;
import com.vuzix.ultralite.Layout;
import com.vuzix.ultralite.UltraliteSDK;
import com.vuzix.ultralite.utils.scroll.LiveText;
import com.vuzix.ultralite.UltraliteSDK.Canvas;
import com.vuzix.ultralite.Anchor;
import com.vuzix.ultralite.TextAlignment;
import com.vuzix.ultralite.TextWrapMode;
import com.vuzix.ultralite.UltraliteColor;


public class MainActivity extends AppCompatActivity {

    protected static final String TAG = MainActivity.class.getSimpleName();

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    // ViewPagerAdapter should be defined (assuming it's in a separate file or as an inner class)
    // For this refactor, let's assume ViewPagerAdapter is correctly defined elsewhere.
    // If not, it would look like: private ViewPagerAdapter viewPagerAdapter;

    private DemoActivityViewModel model;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        if (getSupportActionBar()!=null) getSupportActionBar().hide();

        TabLayout tabLayout   = findViewById(R.id.tab_layout);
        ViewPager2 viewPager  = findViewById(R.id.view_pager);
        viewPager.setAdapter(new ViewPagerAdapter(this));
        viewPager.setUserInputEnabled(false); // disable swipe

        new TabLayoutMediator(tabLayout, viewPager, (tab,pos)->{
            tab.setText(pos==0?"Display":pos==1?"Obsi":"Settings");
        }).attach();

        model = new ViewModelProvider(this).get(DemoActivityViewModel.class);
    }

    // MainActivity is now much cleaner. It only handles its own UI setup.
    // Specific actions (like sending text) will be initiated by Fragments,
    // which will call methods on the shared DemoActivityViewModel.

    // -----------------------------------------------------------------------------------------
    // --- ViewModel: Handles Data, SDK Interaction, and Background Tasks ---
    // -----------------------------------------------------------------------------------------
    public static class DemoActivityViewModel extends AndroidViewModel {
        private static final String VM_TAG = "DemoActivityViewModel"; // Differentiate TAG for ViewModel

        private final UltraliteSDK ultralite;
        private final MutableLiveData<Boolean> running = new MutableLiveData<>(false); // Default to false
        private boolean haveControlOfGlasses = false;
        private String pendingTextToDisplay = null;
        private volatile LiveText activeLiveText;
        private List<String> cachedLines = new ArrayList<>();
        private int currentStartLine = 0;
        private final int numVisible = 8;
        private static final int LINE_HEIGHT_PX = 30;
        private Canvas canvas;
        private final List<Integer> canvasTextIds = new ArrayList<>();
        private boolean canvasMode = false;

        private final Observer<Boolean> controlledObserver = controlled -> {
            Log.d(VM_TAG, "Controlled by me: " + controlled);
            haveControlOfGlasses = controlled;
            if (controlled && pendingTextToDisplay != null) {
                // If we gained control and had pending text, try sending it
                // Decide which method to call based on the original intent
                // For simplicity, let's assume it was for startDisplayFullText
                startDisplayFullText(pendingTextToDisplay); // Or handle based on type of pending action
                pendingTextToDisplay = null;
            } else if (!controlled) {
                // Lost control, maybe clear pending text or show a message
                // pendingTextToDisplay = null; // Clearing if control is lost
                running.postValue(false); // Stop any running indication
            }
        };

        public DemoActivityViewModel(@NonNull Application application) {
            super(application);
            ultralite = UltraliteSDK.get(application);
            ultralite.getControlledByMe().observeForever(controlledObserver);
        }

        public LiveData<Boolean> getRunning() {
            return running;
        }

        private boolean requestControlIfNeeded() {
            if (!haveControlOfGlasses) {
                Log.d(VM_TAG, "Requesting control of glasses...");
                ultralite.requestControl();
                // Control is requested. The controlledObserver will update haveControlOfGlasses.
                // For immediate actions, subsequent SDK calls might fail if control isn't granted yet.
                // This is a simplified model; robust apps might queue actions or wait.
            }
            return haveControlOfGlasses; // Return current known state
        }

        /**
         * Displays simple text using DemoCanvasLayout.runText (likely for short notifications).
         * This method will queue the text if control is not immediately available.
         */
        public void displayTextOnGlasses(String userMessage) {
            if (userMessage == null || userMessage.isEmpty()) return;

            if (requestControlIfNeeded() && haveControlOfGlasses) { // Check if control was already held or immediately granted (less likely for first request)
                startDisplayFullText(userMessage);
            } else {
                Log.d(VM_TAG, "Control not available, queuing text: " + userMessage);
                pendingTextToDisplay = userMessage; // Store for when control is gained
                // If not already requesting, requestControlIfNeeded() above would have initiated it.
            }
        }

        private void startDisplayFullText(String textToDisplayOnGlasses) {
            new Thread(() -> {
                if (!haveControlOfGlasses) { // Double check control before lengthy operation
                    Log.w(VM_TAG, "Lost control before starting displayFullText for: " + textToDisplayOnGlasses);
                    pendingTextToDisplay = textToDisplayOnGlasses; // Re-queue if control lost
                    ultralite.requestControl(); // Attempt to regain control
                    return;
                }

                running.postValue(true);
                Log.d(VM_TAG, "Starting displayFullText: " + textToDisplayOnGlasses);
                try {
                    // Assuming DemoCanvasLayout needs the ViewModel instance for context or methods
                    DemoCanvasLayout.runText(getApplication(), this, ultralite, textToDisplayOnGlasses);
                } catch (Stop stop) {
                    Log.e(VM_TAG, "Stop signal received during displayFullText. Error: " + stop.isError());
                    if (ultralite != null) {
                        if (stop.isError()) {
                            ultralite.sendNotification("DisplayText Error", "An error occurred.", null, "0", null);
                        } else {
                            ultralite.sendNotification("DisplayText Lost", "App lost control", null,"0" , null);
                        }
                        // Consider if releaseControl() should always happen on Stop
                        // ultralite.releaseControl();
                    }
                } catch (Exception e) {
                    Log.e(VM_TAG, "Exception in displayFullText", e);
                    if (ultralite != null) ultralite.sendNotification("App Error", "Display issue", null, "0", null);
                } finally {
                    running.postValue(false);
                }
            }).start();
        }


        /**
         * Displays a large block of scrollable text on the glasses using LiveText.
         */
        public void displayScrollableTextOnGlasses(@NonNull String fullText) {
            if(fullText.isEmpty()) return;
            if(!requestControlIfNeeded()) {    // ask Bluetooth chip for control
                Log.w(VM_TAG,"Control not yet granted – user must retry");
                return;
            }
            // Wait until we really have control
            if(!haveControlOfGlasses) {
                ultralite.getControlledByMe().observeForever(new Observer<Boolean>() {
                    @Override public void onChanged(Boolean b) {
                        if(Boolean.TRUE.equals(b)) {
                            ultralite.getControlledByMe().removeObserver(this);
                            displayScrollableTextOnGlasses(fullText);  // recurse now that control is ours
                        }
                    }
                });
                return;
            }
            // We own the glasses – build the canvas on the UI thread
            new Handler(Looper.getMainLooper()).post(() -> prepareCanvas(fullText));
        }

        /* ========== 2.  Canvas builder – runs on UI thread ========== */
        private void prepareCanvas(@NonNull String src) {
            try {
                ultralite.setLayout(Layout.CANVAS, /*timeout*/0, true, true, 0);
                canvas      = ultralite.getCanvas();
                canvasMode  = true;
                canvas.clearBackground(UltraliteColor.BLACK);

                // ---------- format lines ----------
                String[] raw = src.split("\\r?\\n");
                int digits   = String.valueOf(raw.length).length();
                cachedLines  = new ArrayList<>(raw.length);
                for(int i=0;i<raw.length;i++)
                    cachedLines.add(String.format("%"+digits+"d   %s", i+1,
                            raw[i].replaceAll("\\h+", " ").trim()));

                // ---------- draw first window ----------
                canvasTextIds.clear();
                currentStartLine = 0;
                int y            = 0;
                int winMax       = Math.min(numVisible, cachedLines.size());

                for(int i=0;i<winMax;i++, y+=LINE_HEIGHT_PX) {
                    int id = canvas.createText(
                            cachedLines.get(i),
                            TextAlignment.LEFT,
                            UltraliteColor.WHITE,
                            Anchor.TOP_LEFT,
                            0, y,
                            Canvas.WIDTH,
                            LINE_HEIGHT_PX,
                            TextWrapMode.CLIP,
                            /*visible*/true);
                    canvasTextIds.add(id);
                }
                canvas.commit(null);
                Log.i(VM_TAG,"Canvas initialised with "+winMax+" lines");
            } catch(Exception e) {
                Log.e(VM_TAG,"prepareCanvas failed",e);
                canvasMode=false;
            }
        }


        /* ========== 3.  Smooth line-by-line scroll & edit ========== */
        public void scrollLines(int delta) {
            if(!canvasMode || canvas==null) return;
            int newStart = Math.max(0,
                    Math.min(currentStartLine + delta, cachedLines.size() - numVisible));
            if(newStart == currentStartLine) return;   // reached top/bottom
            currentStartLine = newStart;

            for(int i=0;i<canvasTextIds.size();i++) {
                int idx  = currentStartLine + i;
                String s = idx < cachedLines.size() ? cachedLines.get(idx) : "";
                canvas.updateText(canvasTextIds.get(i), s);
            }
            canvas.commit(null);
        }

        /* Call this whenever phone-side editor mutates a single line: */
        public void replaceLine(int zeroBasedIndex, @NonNull String newContent) {
            if(zeroBasedIndex<0 || zeroBasedIndex>=cachedLines.size()) return;
            cachedLines.set(zeroBasedIndex, newContent);
            int relative = zeroBasedIndex - currentStartLine;   // is it visible?
            if(relative>=0 && relative<canvasTextIds.size()) {
                canvas.updateText(canvasTextIds.get(relative), newContent);
                canvas.commit(null);
            }
        }


        /**
         * Pauses execution for a specified duration, throwing Stop if control is lost.
         * Used by DemoCanvasLayout.
         * @param ms Duration in milliseconds.
         * @throws Stop If control of glasses is lost during the pause.
         */
        public void pause(long ms) throws Stop {
            try {
                Thread.sleep(ms); // Use Thread.sleep for background thread pauses
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interruption status
                Log.w(VM_TAG, "Pause interrupted", e);
                throw new Stop(true, "Pause interrupted"); // Or a different error state
            }
            if (!haveControlOfGlasses) {
                throw new Stop(false, "Control lost during pause");
            }
        }


        @Override
        protected void onCleared() {
            super.onCleared();
            Log.d(VM_TAG, "ViewModel onCleared");
            if (ultralite != null) {
                ultralite.getControlledByMe().removeObserver(controlledObserver);
                // Release control when the ViewModel is cleared (MainActivity is finishing)
                // This ensures we don't hold onto the glasses unnecessarily.
                if (haveControlOfGlasses) {
                    Log.d(VM_TAG, "Releasing control of glasses.");
                    ultralite.releaseControl();
                }
            }
        }
    } // End of DemoActivityViewModel

    // -----------------------------------------------------------------------------------------
    // --- Utility Classes and Methods ---
    // -----------------------------------------------------------------------------------------

    /**
     * Custom exception to signal stopping background operations.
     */
    public static class Stop extends Exception {
        private final boolean error;
        private final String reason;

        public Stop(boolean error, String reason) {
            super(reason);
            this.error = error;
            this.reason = reason;
        }

        public boolean isError() {
            return error;
        }
        public String getReason() { return reason; }
    }

    /**
     * Loads an image resource and converts it to an LVGLImage.
     * @param context Context for accessing resources.
     * @param resourceId The drawable resource ID.
     * @param singleBit True for 1-bit color depth, false for 2-bit.
     * @return LVGLImage or null if loading fails.
     */
    static LVGLImage loadLVGLImage(@NonNull Context context, int resourceId, boolean singleBit) {
        try {
            BitmapDrawable drawable = (BitmapDrawable) ResourcesCompat.getDrawable(
                    context.getResources(), resourceId, context.getTheme());
            if (drawable != null && drawable.getBitmap() != null) {
                int colorSpace = singleBit ? LVGLImage.CF_INDEXED_1_BIT : LVGLImage.CF_INDEXED_2_BIT;
                return LVGLImage.fromBitmap(drawable.getBitmap(), colorSpace);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading LVGLImage for resource ID: " + resourceId, e);
        }
        return null;
    }

} // End of MainActivity