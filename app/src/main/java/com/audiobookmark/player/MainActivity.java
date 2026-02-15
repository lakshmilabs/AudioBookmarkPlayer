package com.audiobookmark.player;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "AudioBookmark";
    private static final String PREFS_NAME = "AudioBookmarkPrefs";
    private static final String PREF_SELECTED_ACCOUNT = "selected_account";
    private static final String PREF_FILE_URI = "file_uri";
    private static final String PREF_FILE_NAME = "file_name";
    private static final String PREF_POSITION = "playback_position";
    private static final String PREF_BOOKMARKS = "bookmarks_json";
    private static final String PREF_SHARED_BOOKMARKS = "shared_bookmarks_json";
    private static final String PREF_LAST_FOLDER = "last_folder";
    private static final int PERMISSION_REQUEST_ACCOUNTS = 100;
    private static final int REQUEST_CODE_OPEN_FILE = 101;

    private MediaPlayer mediaPlayer;
    private TextView fileNameText;
    private TextView currentTimeText;
    private TextView durationText;
    private TextView speedText;
    private SeekBar seekBar;
    private MaterialButton playPauseButton;
    private MaterialButton speedButton;
    private MaterialButton addBookmarkButton;
    private MaterialButton openFileButton;
    private MaterialButton shareButton;
    private TextView bookmarksListText;

    private Uri currentUri;
    private String currentFileName;
    private List<Integer> bookmarks;
    private Set<Integer> sharedBookmarks; // bookmarks already sent to Keep
    private float[] speedOptions = {1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private int currentSpeedIndex = 0;

    // Used to defer loading a new file after saving unsaved bookmarks
    private Uri pendingUri;
    private Intent pendingIntent;

    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bookmarks = new ArrayList<>();
        sharedBookmarks = new HashSet<>();

        fileNameText = findViewById(R.id.fileNameText);
        currentTimeText = findViewById(R.id.currentTimeText);
        durationText = findViewById(R.id.durationText);
        speedText = findViewById(R.id.speedText);
        seekBar = findViewById(R.id.seekBar);
        playPauseButton = findViewById(R.id.playPauseButton);
        speedButton = findViewById(R.id.speedButton);
        addBookmarkButton = findViewById(R.id.addBookmarkButton);
        openFileButton = findViewById(R.id.openFileButton);
        shareButton = findViewById(R.id.exportButton);
        bookmarksListText = findViewById(R.id.bookmarksListText);

        setupListeners();

        Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            // Opened via "Open with" — load saved state first so we can detect
            // unsaved bookmarks for the previous file, then handle the new file
            loadSavedBookmarksOnly();
            handleIncomingIntent(intent);
        } else {
            // Launched via icon — restore saved state
            restoreState();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            handleIncomingIntent(intent);
        }
    }

    private void handleIncomingIntent(Intent intent) {
        Uri newUri = intent.getData();
        if (newUri == null) return;

        // Try to persist URI permission so we can reopen the file later
        try {
            int flags = intent.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (flags != 0) {
                getContentResolver().takePersistableUriPermission(newUri, flags);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not persist URI permission", e);
        }

        // Check if it's the same file we already have loaded
        if (currentUri != null && currentUri.toString().equals(newUri.toString())) {
            // Same file — just continue, don't clear bookmarks
            return;
        }

        // Different file — check for unsaved bookmarks
        if (hasUnsavedBookmarks()) {
            pendingUri = newUri;
            pendingIntent = intent;
            showSaveFirstDialog();
        } else {
            loadNewFile(newUri);
        }
    }

    /**
     * Load only saved bookmarks/URI/filename into memory (no media player).
     * Used when opening via "Open with" so we can detect unsaved bookmarks
     * for the previous file before loading the new one.
     */
    private void loadSavedBookmarksOnly() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(PREF_FILE_URI, null);
        String savedName = prefs.getString(PREF_FILE_NAME, null);
        String bookmarksJson = prefs.getString(PREF_BOOKMARKS, null);
        String sharedJson = prefs.getString(PREF_SHARED_BOOKMARKS, null);

        if (savedUri == null) return;

        currentUri = Uri.parse(savedUri);
        currentFileName = savedName != null ? savedName : "Unknown";
        bookmarks = jsonToList(bookmarksJson);
        sharedBookmarks = new HashSet<>(jsonToList(sharedJson));
    }

    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUri = prefs.getString(PREF_FILE_URI, null);
        String savedName = prefs.getString(PREF_FILE_NAME, null);
        int savedPosition = prefs.getInt(PREF_POSITION, 0);
        String bookmarksJson = prefs.getString(PREF_BOOKMARKS, null);
        String sharedJson = prefs.getString(PREF_SHARED_BOOKMARKS, null);

        if (savedUri == null) return; // Nothing saved, fresh start

        currentUri = Uri.parse(savedUri);
        currentFileName = savedName != null ? savedName : "Unknown";
        fileNameText.setText(currentFileName);

        // Restore bookmarks
        bookmarks = jsonToList(bookmarksJson);
        sharedBookmarks = new HashSet<>(jsonToList(sharedJson));
        updateBookmarksList();

        // Try to load the media file
        if (!tryLoadMediaPlayer(currentUri, savedPosition)) {
            // File no longer accessible
            showFileUnavailableDialog();
        }
    }

    private boolean tryLoadMediaPlayer(Uri uri, int position) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepare();

            seekBar.setMax(mediaPlayer.getDuration());
            durationText.setText(formatTime(mediaPlayer.getDuration()));

            if (position > 0 && position < mediaPlayer.getDuration()) {
                mediaPlayer.seekTo(position);
                seekBar.setProgress(position);
                currentTimeText.setText(formatTime(position));
            }

            mediaPlayer.setOnCompletionListener(mp -> {
                playPauseButton.setText("Play");
                handler.removeCallbacks(updateSeekBar);
            });

            playPauseButton.setText("Play");
            return true;
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            Log.w(TAG, "Failed to load media: " + e.getMessage());
            mediaPlayer = null;
            return false;
        }
    }

    private void showFileUnavailableDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AudioBookmarkPlayer_Dialog);
        builder.setTitle("File Unavailable");
        builder.setMessage("The file \"" + currentFileName + "\" is no longer accessible.");

        if (hasUnsavedBookmarks()) {
            builder.setMessage("The file \"" + currentFileName + "\" is no longer accessible.\n\nYou have unsaved bookmarks. Would you like to save them to Google Keep?");
            builder.setPositiveButton("Save to Keep", (d, w) -> shareToKeep());
            builder.setNegativeButton("Discard", (d, w) -> clearSavedState());
        } else {
            builder.setPositiveButton("OK", (d, w) -> clearSavedState());
        }
        builder.setCancelable(false);
        builder.show();
    }

    private void showSaveFirstDialog() {
        Log.d(TAG, "showSaveFirstDialog: showing dialog, currentFileName=" + currentFileName + ", bookmarks.size=" + bookmarks.size());
        new AlertDialog.Builder(this, R.style.Theme_AudioBookmarkPlayer_Dialog)
                .setTitle("Unsaved Bookmarks")
                .setMessage("You have unsaved bookmarks for \"" + currentFileName + "\". Save them to Google Keep before opening the new file?")
                .setPositiveButton("Save First", (d, w) -> {
                    Log.d(TAG, "showSaveFirstDialog: Save First clicked");
                    // After the Keep intent returns, we'll load the pending file
                    shareToKeepThenLoadPending();
                })
                .setNegativeButton("Discard", (d, w) -> {
                    Log.d(TAG, "showSaveFirstDialog: Discard clicked");
                    if (pendingUri != null) {
                        loadNewFile(pendingUri);
                        pendingUri = null;
                        pendingIntent = null;
                    }
                })
                .setNeutralButton("Cancel", (d, w) -> {
                    Log.d(TAG, "showSaveFirstDialog: Cancel clicked");
                })
                .setCancelable(true)
                .show();
    }

    private void shareToKeepThenLoadPending() {
        Log.d(TAG, "shareToKeepThenLoadPending: called");
        // Share current bookmarks, then on return load the pending file
        doShareToKeep();
        // After share intent, onResume will check pendingUri
    }

    private void loadNewFile(Uri uri) {
        currentUri = uri;
        currentFileName = getBaseName(uri);
        fileNameText.setText(currentFileName);

        bookmarks.clear();
        sharedBookmarks.clear();

        if (!tryLoadMediaPlayer(uri, 0)) {
            Toast.makeText(this, "Error loading file", Toast.LENGTH_LONG).show();
        }
        updateBookmarksList();
        saveState();
    }

    private void setupListeners() {
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        speedButton.setOnClickListener(v -> changeSpeed());
        addBookmarkButton.setOnClickListener(v -> addBookmark());
        openFileButton.setOnClickListener(v -> openFilePicker());
        shareButton.setOnClickListener(v -> shareToKeep());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    currentTimeText.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            handler.removeCallbacks(updateSeekBar);
        } else {
            mediaPlayer.start();
            handler.post(updateSeekBar);
        }
        playPauseButton.setText(mediaPlayer.isPlaying() ? "Pause" : "Play");
    }

    private void changeSpeed() {
        if (mediaPlayer == null) return;

        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.length;
        float speed = speedOptions[currentSpeedIndex];

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
        }

        speedText.setText(String.format(Locale.US, "%.2fx", speed));
        speedButton.setText(String.format(Locale.US, "Speed: %.2fx", speed));
    }

    private void addBookmark() {
        if (mediaPlayer == null) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        int position = mediaPlayer.getCurrentPosition();
        bookmarks.add(position); // append to end, no sorting
        updateBookmarksList();
        saveState();

        Toast.makeText(this, "Bookmark added: " + formatTime(position), Toast.LENGTH_SHORT).show();
    }

    private void updateBookmarksList() {
        StringBuilder sb = new StringBuilder();
        for (int bookmark : bookmarks) {
            sb.append(formatTime(bookmark)).append("\n");
        }
        bookmarksListText.setText(sb.toString());
    }

    // --- Keep sharing ---

    private void shareToKeep() {
        if (currentFileName == null || currentFileName.isEmpty()) {
            Toast.makeText(this, "No file loaded", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "No bookmarks to share", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!hasUnsavedBookmarks()) {
            Toast.makeText(this, "All bookmarks already saved to Keep", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedAccount = prefs.getString(PREF_SELECTED_ACCOUNT, null);

        if (savedAccount != null) {
            sendToKeep(savedAccount);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_CONTACTS},
                        PERMISSION_REQUEST_ACCOUNTS);
            } else {
                showAccountPicker();
            }
        }
    }

    /**
     * Directly share to Keep, bypassing all checks (used from save-first dialog
     * where we already know there are unsaved bookmarks).
     */
    private void doShareToKeep() {
        Log.d(TAG, "doShareToKeep: called, bookmarks.size=" + bookmarks.size());
        if (bookmarks.isEmpty()) {
            Log.w(TAG, "doShareToKeep: bookmarks empty, returning");
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedAccount = prefs.getString(PREF_SELECTED_ACCOUNT, null);
        Log.d(TAG, "doShareToKeep: savedAccount=" + (savedAccount != null ? "exists" : "null"));

        if (savedAccount != null) {
            Log.d(TAG, "doShareToKeep: calling sendToKeep");
            sendToKeep(savedAccount);
        } else {
            Log.d(TAG, "doShareToKeep: no saved account, showing picker");
            // No account saved yet — need to pick one first
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "doShareToKeep: requesting READ_CONTACTS permission");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_CONTACTS},
                        PERMISSION_REQUEST_ACCOUNTS);
            } else {
                Log.d(TAG, "doShareToKeep: showing account picker");
                showAccountPicker();
            }
        }
    }

    private List<Integer> getUnsharedBookmarks() {
        List<Integer> unshared = new ArrayList<>();
        for (int i = 0; i < bookmarks.size(); i++) {
            // Use index-based tracking: bookmark at index i is "shared" if in sharedBookmarks set
            // We store the index, not the value, to handle duplicate times
            if (!sharedBookmarks.contains(i)) {
                unshared.add(bookmarks.get(i));
            }
        }
        return unshared;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_ACCOUNTS) {
            showAccountPicker();
        }
    }

    private void showAccountPicker() {
        AccountManager accountManager = AccountManager.get(this);
        Account[] allAccounts = accountManager.getAccounts();

        List<String> googleAccounts = new ArrayList<>();
        for (Account account : allAccounts) {
            if ("com.google".equals(account.type) ||
                account.name.contains("@gmail.com") ||
                account.name.contains("@googlemail.com")) {
                googleAccounts.add(account.name);
            }
        }

        if (googleAccounts.isEmpty()) {
            for (Account account : allAccounts) {
                if (account.name.contains("@")) {
                    googleAccounts.add(account.name);
                }
            }
        }

        if (googleAccounts.isEmpty()) {
            Toast.makeText(this, "No Google accounts found on this device", Toast.LENGTH_LONG).show();
            sendToKeep(null);
            return;
        }

        String[] accountNames = googleAccounts.toArray(new String[0]);

        new AlertDialog.Builder(this, R.style.Theme_AudioBookmarkPlayer_Dialog)
                .setTitle("Select Google Account for Keep")
                .setItems(accountNames, (dialog, which) -> {
                    String selectedAccount = accountNames[which];
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(PREF_SELECTED_ACCOUNT, selectedAccount)
                            .apply();
                    sendToKeep(selectedAccount);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendToKeep(String account) {
        if (bookmarks.isEmpty()) {
            Log.w(TAG, "sendToKeep: bookmarks empty");
            return;
        }
        if (currentFileName == null || currentFileName.isEmpty()) {
            Log.w(TAG, "sendToKeep: currentFileName null or empty");
            currentFileName = "Audio Bookmarks";
        }

        Log.d(TAG, "sendToKeep: sending " + bookmarks.size() + " bookmarks for " + currentFileName);

        // Always send ALL bookmarks (Keep can't append to existing notes,
        // so each share creates a complete note — user deletes the old one)
        StringBuilder body = new StringBuilder();
        body.append("#Edit-times\n\n");
        for (int bookmark : bookmarks) {
            body.append(formatTime(bookmark)).append("\n");
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, currentFileName);
        shareIntent.putExtra(Intent.EXTRA_TEXT, body.toString());
        shareIntent.setPackage("com.google.android.keep");

        try {
            startActivity(shareIntent);
            // Mark all current bookmarks as shared
            for (int i = 0; i < bookmarks.size(); i++) {
                sharedBookmarks.add(i);
            }
            saveState();
            Log.d(TAG, "sendToKeep: Keep intent launched successfully");
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "sendToKeep: Keep not found, trying generic chooser");
            shareIntent.setPackage(null);
            try {
                startActivity(Intent.createChooser(shareIntent, "Share bookmarks"));
                for (int i = 0; i < bookmarks.size(); i++) {
                    sharedBookmarks.add(i);
                }
                saveState();
            } catch (android.content.ActivityNotFoundException e2) {
                Toast.makeText(this, "No app found to share bookmarks", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- State persistence ---

    private void saveState() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        if (currentUri != null) {
            editor.putString(PREF_FILE_URI, currentUri.toString());
        }
        if (currentFileName != null) {
            editor.putString(PREF_FILE_NAME, currentFileName);
        }
        int position = (mediaPlayer != null) ? mediaPlayer.getCurrentPosition() : 0;
        editor.putInt(PREF_POSITION, position);
        editor.putString(PREF_BOOKMARKS, listToJson(bookmarks));
        editor.putString(PREF_SHARED_BOOKMARKS, listToJson(new ArrayList<>(sharedBookmarks)));
        editor.apply();
    }

    private void clearSavedState() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.remove(PREF_FILE_URI);
        editor.remove(PREF_FILE_NAME);
        editor.remove(PREF_POSITION);
        editor.remove(PREF_BOOKMARKS);
        editor.remove(PREF_SHARED_BOOKMARKS);
        editor.apply();
        currentUri = null;
        currentFileName = null;
        bookmarks.clear();
        sharedBookmarks.clear();
        updateBookmarksList();
        fileNameText.setText("No file loaded");
    }

    private boolean hasUnsavedBookmarks() {
        if (bookmarks.isEmpty()) return false;
        return !getUnsharedBookmarks().isEmpty();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If we have a pending file to load (after saving bookmarks to Keep), load it now
        if (pendingUri != null) {
            Uri uri = pendingUri;
            pendingUri = null;
            pendingIntent = null;
            loadNewFile(uri);
        }
    }

    // --- JSON helpers ---

    private String listToJson(List<Integer> list) {
        JSONArray arr = new JSONArray();
        for (int val : list) {
            arr.put(val);
        }
        return arr.toString();
    }

    private List<Integer> jsonToList(String json) {
        List<Integer> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getInt(i));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse bookmarks JSON", e);
        }
        return list;
    }

    // --- Utilities ---

    private Runnable updateSeekBar = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                int currentPosition = mediaPlayer.getCurrentPosition();
                seekBar.setProgress(currentPosition);
                currentTimeText.setText(formatTime(currentPosition));
                handler.postDelayed(this, 100);
            }
        }
    };

    private String formatTime(int milliseconds) {
        int seconds = milliseconds / 1000;
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
    }

    private String getBaseName(Uri uri) {
        String displayName = null;

        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to query display name", e);
            }
        }

        if (displayName == null) {
            displayName = uri.getLastPathSegment();
        }

        if (displayName == null) return "Unknown";

        int dotIndex = displayName.lastIndexOf('.');
        if (dotIndex > 0) {
            return displayName.substring(0, dotIndex);
        }
        return displayName;
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        // Note: EXTRA_INITIAL_URI requires API 26+, so we can't restore last folder on older devices
        // The system file picker will remember its own last location

        startActivityForResult(intent, REQUEST_CODE_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_OPEN_FILE && resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri selectedUri = data.getData();

                // Take persistable permission
                try {
                    int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(selectedUri, flags);
                } catch (Exception e) {
                    Log.w(TAG, "Could not take persistable URI permission", e);
                }

                // Check for unsaved bookmarks before loading new file
                if (currentUri != null && !currentUri.toString().equals(selectedUri.toString()) && hasUnsavedBookmarks()) {
                    pendingUri = selectedUri;
                    showSaveFirstDialog();
                } else {
                    loadNewFile(selectedUri);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        handler.removeCallbacks(updateSeekBar);
    }
}
