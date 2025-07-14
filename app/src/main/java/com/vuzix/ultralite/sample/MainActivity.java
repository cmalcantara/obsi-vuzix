package com.vuzix.ultralite.sample;

// Keep your existing imports
import android.app.Application;
// import android.content.Context; // No longer needed directly for SharedPreferences here
// import android.content.SharedPreferences; // No longer needed directly for SharedPreferences here
// ... other imports ...
import android.content.Context;
import android.os.Bundle; // Keep
// Remove View initializations that are now in DisplayFragment
// import android.widget.Button;
// import android.widget.EditText;
// import android.widget.ImageView;
// import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2; // Add this

import com.google.android.material.tabs.TabLayout; // Add this
import com.google.android.material.tabs.TabLayoutMediator; // Add this
import com.vuzix.ultralite.LVGLImage; // Keep
import com.vuzix.ultralite.UltraliteSDK; // Keep
// ... other utility imports like Context, ResourcesCompat etc.

public class MainActivity extends AppCompatActivity {

    protected static final String TAG = MainActivity.class.getSimpleName();
    // SharedPreferences constants can be moved to DisplayFragment or a common file

    private TabLayout tabLayout;
    private ViewPager2 viewPager;
    private ViewPagerAdapter viewPagerAdapter;

    // Your DemoActivityViewModel and other necessary fields remain
    private DemoActivityViewModel model;
    // private UltraliteSDK ultralite; // This will be initialized and used in DisplayFragment mainly


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity); // This now loads the layout with TabLayout and ViewPager

        tabLayout = findViewById(R.id.tab_layout);
        viewPager = findViewById(R.id.view_pager);

        viewPagerAdapter = new ViewPagerAdapter(this);
        viewPager.setAdapter(viewPagerAdapter);

        // Link TabLayout with ViewPager2
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
            }
        }).attach();

        // Initialize ViewModel (if it's Activity-scoped)
        // If your ViewModel is truly Activity-scoped and shared between fragments, this is fine.
        model = new ViewModelProvider(this).get(DemoActivityViewModel.class);

        // NOTE: All the UI initialization (ImageViews, Buttons, EditText) and their listeners
        // for the "Display" page should now be in DisplayFragment.java.
        // The UltraliteSDK observers related to those specific UI elements also move there.
    }

    // sendSampleNotification and DemoActivityViewModel, Stop, loadLVGLImage can remain in MainActivity
    // if they are general utility or if the ViewModel is activity-scoped.
    // However, if DemoActivityViewModel is tightly coupled with the "Display" logic,
    // consider if parts of it should also move or be accessed via the DisplayFragment.

    // ... (Your existing DemoActivityViewModel, Stop class, loadLVGLImage method) ...
    // Make sure DemoActivityViewModel is correctly structured if it's being used by DisplayFragment
    public static class DemoActivityViewModel extends AndroidViewModel {
        // ... (existing ViewModel code) ...
        // Ensure methods like displayText are public if called from DisplayFragment

        private final UltraliteSDK ultralite;
        private final MutableLiveData<Boolean> running = new MutableLiveData<>();
        private boolean haveControlOfGlasses;
        private String pendingTextToDisplay = null; // Keep if control logic is here


        public DemoActivityViewModel(@NonNull Application application) {
            super(application);
            ultralite = UltraliteSDK.get(application);
            ultralite.getControlledByMe().observeForever(controlledObserver);
        }

        // Renamed to avoid confusion with the old displayText
        public void displayTextOnGlasses(String userMessage) {
            if (haveControlOfGlasses) {
                startDisplayFullText(userMessage);
            } else {
                pendingTextToDisplay = userMessage; // Store original user message
                if (ultralite != null) {
                    ultralite.requestControl();
                }
            }
        }

        private void startDisplayFullText(String textToDisplayOnGlasses) {
            new Thread(() -> {
                if (haveControlOfGlasses && ultralite != null) {
                    running.postValue(true);
                    try {
                        DemoCanvasLayout.runText(getApplication(), this, ultralite, textToDisplayOnGlasses);
                    } catch (MainActivity.Stop stop) {
                        // ... (existing error handling) ...
                    }
                    running.postValue(false);
                }
            }).start();
        }

        private final Observer<Boolean> controlledObserver = controlled -> {
            if (controlled) {
                haveControlOfGlasses = true;
                if (pendingTextToDisplay != null) {
                    startDisplayFullText(pendingTextToDisplay);
                    pendingTextToDisplay = null;
                }
            } else {
                haveControlOfGlasses = false;
                // pendingTextToDisplay = null; // Or keep it to reshow when reconnected
            }
        };

        @Override
        protected void onCleared() {
            super.onCleared(); // Important to call super
            if (ultralite != null) {
                ultralite.releaseControl();
                // We can delay removing the observer to allow us to be notified of losing control
                // Or we could have just set our state from here.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (ultralite != null) { // Check again in case it was cleared
                        ultralite.getControlledByMe().removeObserver(controlledObserver);
                    }
                }, 500);
            }
        }

        // Make this public if DisplayFragment calls it
        public void displayText(String text) {
            if (haveControlOfGlasses) {
                startDisplayText(text);
            } else {
                pendingTextToDisplay = text;
                if (ultralite != null) {
                    ultralite.requestControl();
                }
            }
        }

        private void startDisplayText(String textToDisplay) {
            new Thread(() -> {
                if(haveControlOfGlasses && ultralite != null) { // Add null check for ultralite
                    running.postValue(true);
                    try {
                        // Pass 'this' as the DemoActivityViewModel instance if DemoCanvasLayout needs it
                        DemoCanvasLayout.runText(getApplication(), this, ultralite, textToDisplay);
                    } catch (MainActivity.Stop stop) { // Ensure Stop is correctly referenced
                        if (ultralite != null) ultralite.releaseControl();
                        if (stop.error) {
                            if (ultralite != null) ultralite.sendNotification("DisplayText Error", "An error occurred.");
                        } else {
                            if (ultralite != null) ultralite.sendNotification("DisplayText Lost", "App lost control of the glasses");
                        }
                    }
                    running.postValue(false);
                }
            }).start();
        }

        public void pause() throws MainActivity.Stop { // Ensure Stop is correctly referenced
            pause(2000);
        }

        public void pause(long ms) throws MainActivity.Stop { // Ensure Stop is correctly referenced
            android.os.SystemClock.sleep(ms);
            if (!haveControlOfGlasses) {
                throw new MainActivity.Stop(false);
            }
        }

        // Add getter for running LiveData if DisplayFragment needs to observe it
        public LiveData<Boolean> getRunning() {
            return running;
        }
    }

    // Stop class, loadLVGLImage method remain here
    public static class Stop extends Exception {
        private final boolean error;
        public Stop(boolean error) {
            this.error = error;
        }
    }

    static LVGLImage loadLVGLImage(Context context, int resource, boolean singleBit) {
        // ... (implementation)
        android.graphics.drawable.BitmapDrawable drawable = (android.graphics.drawable.BitmapDrawable)
                androidx.core.content.res.ResourcesCompat.getDrawable(
                        context.getResources(), resource, context.getTheme());
        if (drawable == null) return null; // Add null check
        int colorSpace = singleBit ? LVGLImage.CF_INDEXED_1_BIT : LVGLImage.CF_INDEXED_2_BIT ;
        return LVGLImage.fromBitmap(drawable.getBitmap(), colorSpace);
    }
}
