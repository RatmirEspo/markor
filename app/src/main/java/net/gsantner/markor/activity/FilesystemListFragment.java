package net.gsantner.markor.activity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.mobsandgeeks.adapters.Sectionizer;
import com.mobsandgeeks.adapters.SimpleSectionAdapter;

import net.gsantner.markor.R;
import net.gsantner.markor.adapter.NotesAdapter;
import net.gsantner.markor.dialog.ConfirmDialog;
import net.gsantner.markor.dialog.FilesystemDialog;
import net.gsantner.markor.dialog.RenameDialog;
import net.gsantner.markor.model.Constants;
import net.gsantner.markor.model.MarkorSingleton;
import net.gsantner.markor.util.AppSettings;
import net.gsantner.markor.util.ContextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FilesystemListFragment extends Fragment {

    public static final int RENAME_CONTEXT_BUTTON_ID = 103;

    private View layoutView;

    private ListView filesListView;
    private TextView hintTextView;

    private File rootDir;
    private File currentDir;

    private MarkorSingleton markorSingleton;

    private ArrayList<File> filesCurrentlyShown = new ArrayList<File>();

    private NotesAdapter filesAdapter;
    private SimpleSectionAdapter<File> simpleSectionAdapter;
    private Sectionizer<File> sectionizer = new Sectionizer<File>() {
        @Override
        public String getSectionTitleForItem(File instance) {
            return instance.isDirectory() ? getString(R.string.folders) : getString(R.string.files);
        }
    };
    private ActionMode actionMode;
    private List<File> selectedItems = new ArrayList<File>();

    public FilesystemListFragment() {
        super();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Context context = getActivity();
        ContextUtils.get().setAppLanguage(AppSettings.get().getLanguage());
        layoutView = inflater.inflate(R.layout.filesystemlist__fragment, container, false);
        hintTextView = (TextView) layoutView.findViewById(R.id.empty_hint);
        filesListView = (ListView) layoutView.findViewById(R.id.notes_listview);

        filesAdapter = new NotesAdapter(context, 0, filesCurrentlyShown);
        simpleSectionAdapter =
                new SimpleSectionAdapter<>(context, filesAdapter, R.layout.filesystemlist__fragment__breadcrumbs_header, R.id.notes_fragment_section_text, sectionizer);

        filesListView.setOnItemClickListener(new NotesItemClickListener());
        filesListView.setMultiChoiceModeListener(new ActionModeCallback());
        filesListView.setAdapter(simpleSectionAdapter);
        rootDir = getRootFolderFromPrefsOrDefault();

        filesListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            private int mLastFirstVisibleItem;
            boolean IS_SCROLLING;

            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
                    IS_SCROLLING = true;
                } else {
                    IS_SCROLLING = false;
                }
                int firstVisibleItem = view.getFirstVisiblePosition();

                mLastFirstVisibleItem = firstVisibleItem;
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            }
        });

        return layoutView;
    }

    @Override
    public void onResume() {
        markorSingleton = MarkorSingleton.getInstance();
        File possiblyNewRootDir = getRootFolderFromPrefsOrDefault();
        if (possiblyNewRootDir != rootDir) {
            rootDir = possiblyNewRootDir;
            currentDir = possiblyNewRootDir;
        }
        retrieveCurrentFolder();
        listFilesInDirectory(getCurrentDir());
        super.onResume();
    }

    private File getRootFolderFromPrefsOrDefault() {
        return new File(AppSettings.get().getSaveDirectory());
    }

    @Override
    public void onPause() {
        saveCurrentFolder();
        super.onPause();
    }

    private void retrieveCurrentFolder() {
        AppSettings appSettings = AppSettings.get();
        if (appSettings.isRememberLastDirectory()) {
            String rememberedDir = appSettings.getLastOpenedDirectory();
            currentDir = (rememberedDir != null) ? new File(rememberedDir) : null;
        }

        // Two-fold check, in case user doesn't have the preference to remember directories enabled
        // This code remembers last directory WITHIN the app (not leaving it)
        if (currentDir == null) {
            currentDir = (markorSingleton.getNotesLastDirectory() != null) ? markorSingleton.getNotesLastDirectory() : rootDir;
        }
    }

    private void saveCurrentFolder() {
        AppSettings appSettings = AppSettings.get();
        SharedPreferences pm = PreferenceManager.getDefaultSharedPreferences(getActivity().getApplicationContext());
        if (appSettings.isRememberLastDirectory()) {
            String saveDir = (currentDir == null) ? rootDir.getAbsolutePath() : currentDir.getAbsolutePath();
            appSettings.setLastOpenedDirectory(saveDir);
        }

        markorSingleton.setNotesLastDirectory(currentDir);
    }

    private void confirmDelete() {
        FragmentManager fragManager = getFragmentManager();
        ConfirmDialog confirmDialog = new ConfirmDialog();
        confirmDialog.show(fragManager, Constants.CONFIRM_DELETE_DIALOG_TAG);
    }

    private void promptForDirectory() {
        android.support.v4.app.FragmentManager fragManager = ((AppCompatActivity) getActivity()).getSupportFragmentManager();

        Bundle args = new Bundle();
        args.putString(Constants.FILESYSTEM_ACTIVITY_ACCESS_TYPE_KEY, Constants.FILESYSTEM_FOLDER_ACCESS_TYPE);

        FilesystemDialog filesystemDialog = new FilesystemDialog();
        filesystemDialog.setArguments(args);
        filesystemDialog.show(fragManager, Constants.FILESYSTEM_MOVE_DIALOG_TAG);
    }


    public void listFilesInDirectory(File directory) {
        reloadFiles(directory);
        broadcastDirectoryChange(directory, rootDir);
        showEmptyDirHintIfEmpty();
        reloadAdapter();
    }

    private void broadcastDirectoryChange(File directory, File rootDir) {
        Intent broadcast = new Intent();
        broadcast.setAction(Constants.CURRENT_FOLDER_CHANGED);
        broadcast.putExtra(Constants.CURRENT_FOLDER, directory);
        broadcast.putExtra(Constants.ROOT_DIR, rootDir);
        getActivity().sendBroadcast(broadcast);
        clearSearchFilter();
    }

    private void reloadFiles(File directory) {

        try {
            // Load from SD card
            filesCurrentlyShown = MarkorSingleton.getInstance().addFilesFromDirectory(directory, new ArrayList<File>());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void reloadAdapter() {
        if (filesAdapter != null) {
            filesAdapter = new NotesAdapter(getActivity().getApplicationContext(), 0, filesCurrentlyShown);
            simpleSectionAdapter =
                    new SimpleSectionAdapter<>(getActivity().getApplicationContext(), filesAdapter, R.layout.filesystemlist__fragment__breadcrumbs_header, R.id.notes_fragment_section_text, sectionizer);
            filesListView.setAdapter(simpleSectionAdapter);
            simpleSectionAdapter.notifyDataSetChanged();
        }
    }

    public void goToPreviousDir() {
        if (currentDir != null) {
            currentDir = currentDir.getParentFile();
        }

        listFilesInDirectory(getCurrentDir());
    }

    private void showEmptyDirHintIfEmpty() {
        if (markorSingleton.isDirectoryEmpty(filesCurrentlyShown)) {
            hintTextView.setVisibility(View.VISIBLE);
            hintTextView.setText(getString(R.string.empty_directory));
        } else {
            hintTextView.setVisibility(View.INVISIBLE);
        }
    }

    public File getCurrentDir() {
        return (currentDir == null) ? getRootDir() : currentDir.getAbsoluteFile();
    }

    public File getRootDir() {
        return rootDir.getAbsoluteFile();
    }

    public ListView getFilesListView() {
        return filesListView;
    }

    public NotesAdapter getFilesAdapter() {
        return filesAdapter;
    }

    public void finishActionMode() {
        actionMode.finish();
    }

    /**
     * Search
     **/
    public void search(CharSequence query) {
        if (query.length() > 0) {
            filesAdapter.getFilter().filter(query);
            simpleSectionAdapter.notifyDataSetChanged();
        }
    }

    public void clearSearchFilter() {
        filesAdapter.getFilter().filter("");
        simpleSectionAdapter.notifyDataSetChanged();
        reloadAdapter();
    }

    public List<File> getSelectedItems() {
        return selectedItems;
    }

    public boolean onRooDir() {
        return markorSingleton.isRootDir(currentDir, rootDir);
    }

    private class ActionModeCallback implements ListView.MultiChoiceModeListener {

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.notes_context_menu, menu);
            mode.setTitle(getResources().getString(R.string.select_elements));
            return true;
        }


        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            actionMode = mode;
            switch (item.getItemId()) {
                case R.id.context_menu_delete:
                    confirmDelete();
                    return true;
                case R.id.context_menu_move:
                    promptForDirectory();
                    return true;
                case RENAME_CONTEXT_BUTTON_ID:
                    promptForNewName(selectedItems.get(0));
                    return true;
                default:
                    return false;
            }
        }

        private void promptForNewName(File renameable) {
            FragmentManager fragManager = getFragmentManager();
            Bundle args = new Bundle();
            args.putString(Constants.SOURCE_FILE, renameable.getAbsolutePath());
            RenameDialog renameDialog = new RenameDialog();
            renameDialog.setArguments(args);
            renameDialog.show(fragManager, Constants.RENAME_DIALOG_TAG);
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode actionMode, int i, long l, boolean checked) {

            switch (filesListView.getCheckedItemCount()) {
                case 0:
                    actionMode.setSubtitle(null);
                    hideRenameButton(actionMode);
                    break;
                case 1:
                    actionMode.setSubtitle(getResources().getString(R.string.one_item_selected));
                    manageClickedVIew(i, checked);
                    showRenameButton(actionMode);
                    break;
                default:
                    manageClickedVIew(i, checked);
                    actionMode.setSubtitle(String.format(getResources().getString(R.string.more_items_selected), filesListView.getCheckedItemCount()));
                    hideRenameButton(actionMode);
                    break;
            }
        }

        private void manageClickedVIew(int i, boolean checked) {
            if (checked) {
                selectedItems.add((File) simpleSectionAdapter.getItem(i));
            } else {
                selectedItems.remove((File) simpleSectionAdapter.getItem(i));
            }
        }

        private void hideRenameButton(ActionMode actionMode) {
            showRenameContextButton(actionMode.getMenu(), false);
        }

        private void showRenameButton(ActionMode actionMode) {
            showRenameContextButton(actionMode.getMenu(), true);
        }

        private void showRenameContextButton(Menu menu, boolean show) {
            if (show) {
                menu.add(Menu.FIRST + 1, RENAME_CONTEXT_BUTTON_ID, Menu.FIRST, R.string.rename)
                        .setIcon(R.drawable.ic_edit_light);

            } else {
                menu.setGroupVisible(1, false);
                menu.removeItem(RENAME_CONTEXT_BUTTON_ID);
            }
            ;
        }
    }

    ;

    private class NotesItemClickListener implements android.widget.AdapterView.OnItemClickListener {
        @Override
        public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
            File file = (File) simpleSectionAdapter.getItem(i);
            Context context = view.getContext();

            // Refresh list if directory, else import
            if (file.isDirectory()) {
                currentDir = file;
                listFilesInDirectory(file);
            } else {

                File note = (File) simpleSectionAdapter.getItem(i);
                Intent intent;

                if (AppSettings.get().isPreviewFirst()) {
                    intent = new Intent(context, PreviewActivity.class);

                    // .replace is a workaround for Markdown lists requiring two \n characters
                    if (note != null) {
                        Uri uriBase = null;
                        if (note.getParentFile() != null) {
                            uriBase = Uri.parse(note.getParentFile().toURI().toString());
                        }

                        intent.putExtra(Constants.MD_PREVIEW_BASE, uriBase.toString());
                    }

                    Uri noteUri = Uri.parse(note.toURI().toString());
                    String content = MarkorSingleton.getInstance().readFileUri(noteUri, context);
                    intent.putExtra(Constants.MD_PREVIEW_KEY, content.replace("\n-", "\n\n-"));
                } else {
                    intent = new Intent(context, NoteActivity.class);
                }
                intent.putExtra(Constants.NOTE_KEY, note);

                startActivity(intent);
                getActivity().overridePendingTransition(R.anim.anim_slide_in_left, R.anim.anim_slide_out_left);
            }
        }
    }

    private class PreviousDirClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            goToPreviousDir();
        }
    }
}