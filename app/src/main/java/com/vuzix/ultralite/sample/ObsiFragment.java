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

public class ObsiFragment extends Fragment {

    private static final String TAG = "ObsiFragment";
    private static final String PREFS_NAME = "ObsiFragmentPrefs";
    private static final String KEY_MARKDOWN_URI = "markdown_uri";

    private Button buttonSelectFile;
    private TextView textViewMarkdownContent;
    private Markwon markwon;
    private Button buttonSendToGlasses;
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

        buttonSelectFile = view.findViewById(R.id.buttonSelectFile);
        textViewMarkdownContent = view.findViewById(R.id.textViewMarkdownContent);
        buttonSendToGlasses = view.findViewById(R.id.buttonSendToGlasses);

        buttonSelectFile.setOnClickListener(v -> openFilePicker());
        buttonSendToGlasses.setOnClickListener(v -> sendMarkdownToGlasses());

        return view;
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
        Uri savedUri = getSavedMarkdownFileUri();
        if (savedUri != null) {
            // Check if we still have permission to read this URI
            try {
                // Attempt to open an InputStream to check if permission is still valid
                InputStream inputStream = requireContext().getContentResolver().openInputStream(savedUri);
                if (inputStream != null) {
                    inputStream.close(); // Close it immediately, we just needed to check
                    Log.d(TAG, "Permission for URI still valid: " + savedUri);
                    loadAndDisplayMarkdown(savedUri);
                } else {
                    Log.w(TAG, "Failed to open input stream for saved URI (permission likely lost): " + savedUri);
                    clearSavedUriAndInformUser();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException loading saved URI (permission lost): " + savedUri, e);
                clearSavedUriAndInformUser();
            } catch (IOException e) {
                Log.e(TAG, "IOException checking saved URI: " + savedUri, e);
                // Decide if you want to clear or just show an error
                textViewMarkdownContent.setText("Error checking previously selected file.");
            }
        } else {
            Log.d(TAG, "No saved Markdown URI found.");
            textViewMarkdownContent.setText("Please select a Markdown file.");
        }
    }

    private void clearSavedUriAndInformUser() {
        SharedPreferences prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_MARKDOWN_URI).apply();
        textViewMarkdownContent.setText("Permission to access the previously selected file was lost. Please select the file again.");
        Toast.makeText(getContext(), "Please re-select the Markdown file.", Toast.LENGTH_LONG).show();
    }


    private void loadAndDisplayMarkdown(Uri uri) {
        StringBuilder stringBuilder = new StringBuilder();
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(inputStream)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append('\n');
            }
            currentMarkdownContent = stringBuilder.toString(); // Store the raw content
            if (!TextUtils.isEmpty(currentMarkdownContent)) {
                markwon.setMarkdown(textViewMarkdownContent, currentMarkdownContent); // Display rendered in app
            } else {
                currentMarkdownContent = ""; // Clear if empty
                textViewMarkdownContent.setText("Selected file is empty.");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading Markdown file: " + uri.toString(), e);
            currentMarkdownContent = ""; // Clear on error
            textViewMarkdownContent.setText("Error reading file: " + e.getMessage());
            Toast.makeText(getContext(), "Error reading file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } catch (SecurityException se) {
            Log.e(TAG, "SecurityException while reading Markdown file: " + uri.toString(), se);
            currentMarkdownContent = ""; // Clear on error
            textViewMarkdownContent.setText("Permission denied. Please re-select the file.");
            Toast.makeText(getContext(), "Permission denied. Please re-select the file.", Toast.LENGTH_LONG).show();
            clearSavedUriAndInformUser();
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
