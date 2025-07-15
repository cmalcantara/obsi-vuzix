package com.vuzix.ultralite.sample;

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
        setContentView(R.layout.main_activity); // Should contain R.id.tab_layout and R.id.view_pager

        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        // Make sure ViewPagerAdapter is correctly instantiated
        ViewPagerAdapter viewPagerAdapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(viewPagerAdapter);

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Display");
                    break;
                case 1:
                    tab.setText("Obsi");
                    break;
                case 2:
                    tab.setText("Settings");
                    break;
                default:
                    // Optional: handle unexpected positions
                    break;
            }
        }).attach();

        // Initialize ViewModel
        model = new ViewModelProvider(this).get(DemoActivityViewModel.class);

        // Any observers for LiveData from the ViewModel that affect MainActivity directly
        // would be set up here. For example, if 'running' status changed something in MainActivity's UI.
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
        public void displayScrollableTextOnGlasses(String fullText) {
            if (fullText == null || fullText.isEmpty()) {
                Log.d(VM_TAG, "Scrollable text is empty, not sending.");
                return;
            }

            if (!requestControlIfNeeded()) {
                Log.w(VM_TAG, "LiveText: Could not gain control. Queuing or failing is an option.");
                // For this example, we'll just log and not proceed if initial requestControl fails
                // A more robust solution might queue this action like pendingTextToDisplay
                return;
            }
            // If control is pending, subsequent calls might fail. For LiveText, better to wait for haveControlOfGlasses
            if (!haveControlOfGlasses) {
                Log.w(VM_TAG, "LiveText: Control not yet confirmed. Consider queuing this operation.");
                // Store as a different type of pending action if needed, or rely on user retrying.
                return;
            }

            new Thread(() -> {
                if (!haveControlOfGlasses) { // Re-check before starting thread work
                    Log.w(VM_TAG, "LiveText: Lost control before starting.");
                    running.postValue(false);
                    return;
                }

                running.postValue(true); // Indicate activity
                Log.d(VM_TAG, "Starting displayScrollableTextOnGlasses (line-by-line).");
                LiveText liveTextSender = null;

                String markdownContent = fullText;

                try {
                    int lineHeight = 30; //[30-120]
                    int lineWidth = 640; //[30-640]
                    int startingLineIndex = 0; //Range: [ 0 - ((480/lineHeight)-1)]
                    int numSlicesVisible = 8; //Min=1. Max=((480/lineHeight)-startingLineIndex).
                    final android.text.TextPaint textPaint = null; //default textPaint

                    ultralite.setLayout(Layout.SCROLL, 0, true, true, 0);
                    liveTextSender = new LiveText(ultralite, lineHeight, lineWidth,
                            startingLineIndex, numSlicesVisible, textPaint);
                    Log.w(VM_TAG, markdownContent);


                    //Preprocessing to work properly in displaying to the device
                    // 1. Split on line breaks (handles both "\n" and "\r\n")
                    String[] lines = markdownContent.split("\\r?\\n");

                    // 2. Clean up each line: collapse horizontal whitespace, then trim
                    for (int i = 0; i < lines.length; i++) {
                        // \\s matches all whitespace (incl. newlines), so instead
                        // use \\h (horizontal whitespace) or explicit [ \\t\\f\\r]
                        lines[i] = lines[i]
                                .replaceAll("\\h+", " ")  // collapse spaces, tabs, etc., but NOT newlines
                                .trim();
                    }

                    // 3. Re-join with a single "\n" to restore line breaks
                    markdownContent = String.join("\n", lines);

                    liveTextSender.sendText(markdownContent);

                    /*
                    // Split the fullText into lines
                    String[] lines = textB.split("\\r?\\n"); // Handles both \n and \r\n
                    String currentTextToSend = "";
                    int lineCount = 0;


                    for (String line : lines) {
                        if (!haveControlOfGlasses) { // Check control before each send
                            Log.w(VM_TAG, "LiveText (line-by-line): Lost control during sending lines.");
                            throw new Stop(false, "Control lost while sending lines");
                        }

                        // Append lines together, similar to DemoScrollLiveText
                        currentTextToSend += line + "\n"; // Add newline back for proper formatting by LiveText

                        // --- CRITICAL CHANGE ---
                        // Send the *accumulated* text so LiveText can manage the diff
                        Log.w(VM_TAG, "just before sending first line");
                        currentTextToSend = currentTextToSend
                                .replaceAll("\\s+", " ")
                                .trim();
                        Log.w(VM_TAG, currentTextToSend);
                        liveTextSender.sendText(currentTextToSend);

                        lineCount++;
                        Log.d(VM_TAG, "LiveText: Sent line " + lineCount + ". Total length: " + currentTextToSend.length());

                        // Optional: Add a small delay to simulate real-time data and observe
                        // Be careful with Thread.sleep on the main thread if this wasn't on a background thread.
                        // Since this is already in a new Thread, it's okay here.
                        try {
                            Thread.sleep(100); // 100ms delay between sending lines - adjust as needed for testing
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            Log.w(VM_TAG, "Line sending delay interrupted.");
                            break; // Exit loop if interrupted
                        }
                    }
                    */


                    Log.d(VM_TAG, "LiveText: Finished sending all lines.");

                    // LiveText typically stays active until cleared or layout changes.
                    // User scrolls on the device. No need for Thread.sleep here usually.

                } catch (IllegalArgumentException iae) { // Catch the numSlicesVisible error specifically
                    Log.e(VM_TAG, "LiveText (line-by-line) Setup Error: " + iae.getMessage(), iae);
                    if (ultralite != null) ultralite.sendNotification("App Error", "LiveText Setup. Check numSlices", null, "LTSERR", null);
                } catch (Exception e) { // Catch other exceptions, including StringIndexOutOfBoundsException
                    Log.e(VM_TAG, "Error during line-by-line LiveText operation: " + e.getMessage(), e);
                    if (ultralite != null) {
                        ultralite.sendNotification("App Error", "LiveText Send. Check Logs.", null, "LiveText Send Err", null);
                    }
                } finally {
                    running.postValue(false);
                    // Optional: Decide if/when to clear the LiveText display.
                    // For this test, you might leave it to see the final result.
                    // To clear:
                    // if (ultralite != null && haveControlOfGlasses && liveTextSender != null) {
                    //     ultralite.clearDisplay(Layout.SCROLL.getGroup()); // Or liveTextSender.clear() if available
                    // }
                }
            }).start();
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