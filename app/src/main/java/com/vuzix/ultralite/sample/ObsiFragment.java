package com.vuzix.ultralite.sample; // Adjust package name if needed

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;
import androidx.lifecycle.ViewModelProvider; // For ViewModel access
import io.noties.markwon.Markwon;
import androidx.appcompat.app.AlertDialog;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import com.vuzix.ultralite.UltraliteSDK;

public class ObsiFragment extends Fragment {

    private static final String TAG = "ObsiFragment";
    private static final String PREFS_NAME = "ObsiFragmentPrefs";
    private static final String KEY_MARKDOWN_URI = "markdown_uri";

    private Button buttonSelectFile;
    private Button buttonSendToGlasses;
    private Button buttonClearGlasses;

    private Button buttonViewFile;
    private TextView textViewMarkdownContent;
    private Markwon markwon;
    private MainActivity.DemoActivityViewModel demoActivityViewModel;
    private String currentMarkdownContent = ""; // To store the loaded content


    // ActivityResultLauncher for the file picker
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        // Persist read permission for the URI
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);

                        saveMarkdownFileUri(uri);
                        loadAndDisplayMarkdown(uri);
                    }
                }
            });

    public ObsiFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        markwon = Markwon.create(requireContext());
        demoActivityViewModel = new ViewModelProvider(requireActivity()).get(MainActivity.DemoActivityViewModel.class);

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_obsi, container, false);

        buttonSelectFile   = view.findViewById(R.id.buttonSelectFile);
        buttonSendToGlasses= view.findViewById(R.id.buttonSendToGlasses);
        buttonClearGlasses = view.findViewById(R.id.buttonClearGlasses);
        buttonViewFile     = view.findViewById(R.id.buttonViewFile);

        buttonSelectFile.setOnClickListener(v -> openFilePicker());
        buttonSendToGlasses.setOnClickListener(v -> sendMarkdownToGlasses());

        UltraliteSDK ultralite = UltraliteSDK.get(requireContext().getApplicationContext());
        buttonClearGlasses.setOnClickListener(v -> { if (ultralite != null) ultralite.releaseControl(); });

        buttonViewFile.setOnClickListener(v -> showFilePopup());

        return view;
    }

    private void showFilePopup() {
        if (TextUtils.isEmpty(currentMarkdownContent)) {
            Toast.makeText(getContext(), "No file loaded.", Toast.LENGTH_SHORT).show();
            return;
        }
        TextView tv = new TextView(requireContext());
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,16,getResources().getDisplayMetrics());
        tv.setPadding(pad,pad,pad,pad);
        markwon.setMarkdown(tv, currentMarkdownContent);

        ScrollView sv = new ScrollView(requireContext());
        sv.addView(tv);

        new AlertDialog.Builder(requireContext())
                .setView(sv)
                .setPositiveButton("Close", null)
                .show();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Try to load the saved Markdown file URI on fragment start
        loadSavedMarkdownFile();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/markdown"); // For .md files specifically
        // You could also use "text/*" for broader text files, but "text/markdown" is better if available
        // Or "*/*" and then filter by extension if needed

        // For persistent URI access
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);


        try {
            filePickerLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(getContext(), "Cannot open file picker: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Cannot open file picker", e);
        }
    }

    private void saveMarkdownFileUri(Uri uri) {
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_MARKDOWN_URI, uri.toString());
        editor.apply();
        Log.d(TAG, "Saved Markdown URI: " + uri.toString());
    }

    private Uri getSavedMarkdownFileUri() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(KEY_MARKDOWN_URI, null);
        if (uriString != null) {
            Log.d(TAG, "Retrieved Markdown URI: " + uriString);
            return Uri.parse(uriString);
        }
        return null;
    }

    private void loadSavedMarkdownFile() {
        Uri u = getSavedMarkdownFileUri();
        if (u!=null) loadAndDisplayMarkdown(u);
    }

    private void clearSavedUriAndInformUser() {
        requireActivity().getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE)
                .edit().remove(KEY_MARKDOWN_URI).apply();
        Toast.makeText(getContext(),"Please re-select the Markdown file.",Toast.LENGTH_LONG).show();
    }

    private void loadAndDisplayMarkdown(Uri uri) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            currentMarkdownContent = sb.toString();
            if (currentMarkdownContent.isEmpty())
                Toast.makeText(getContext(),"Selected file is empty.",Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            currentMarkdownContent = "";
            Toast.makeText(getContext(),"Error reading file: "+e.getMessage(),Toast.LENGTH_LONG).show();
            Log.e(TAG,"Err reading md",e);
        }
    }

    private void sendMarkdownToGlasses() {
        if (TextUtils.isEmpty(currentMarkdownContent)) {
            Toast.makeText(getContext(), "No Markdown content loaded to send.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (demoActivityViewModel != null) {
            // Pass the application context if your ViewModel method needs it
            demoActivityViewModel.displayScrollableTextOnGlasses(currentMarkdownContent);
            Toast.makeText(getContext(), "Sending to glasses...", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "ViewModel not available.", Toast.LENGTH_SHORT).show();
        }
    }
}
