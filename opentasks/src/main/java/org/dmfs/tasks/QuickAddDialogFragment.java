/*
 * Copyright 2017 dmfs GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dmfs.tasks;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import org.dmfs.android.retentionmagic.SupportDialogFragment;
import org.dmfs.android.retentionmagic.annotations.Parameter;
import org.dmfs.android.retentionmagic.annotations.Retain;
import org.dmfs.provider.tasks.AuthorityUtil;
import org.dmfs.tasks.contract.TaskContract;
import org.dmfs.tasks.contract.TaskContract.TaskLists;
import org.dmfs.tasks.contract.TaskContract.Tasks;
import org.dmfs.tasks.model.ContentSet;
import org.dmfs.tasks.model.TaskFieldAdapters;
import org.dmfs.tasks.utils.RecentlyUsedLists;
import org.dmfs.tasks.utils.TasksListCursorSpinnerAdapter;


/**
 * A quick add dialog. It allows the user to enter a new task without having to deal with the full blown editor interface. At present it support task with a
 * title only, but there is an option to fire up the full editor.
 *
 * @author Marten Gajda <marten@dmfs.org>
 */
public class QuickAddDialogFragment extends SupportDialogFragment
        implements OnEditorActionListener, LoaderManager.LoaderCallbacks<Cursor>, OnItemSelectedListener, OnClickListener, TextWatcher
{

    private final static String ARG_LIST_ID = "list_id";
    private final static String ARG_CONTENT = "content";

    public static final String LIST_LOADER_URI = "uri";
    public static final String LIST_LOADER_FILTER = "filter";

    public static final String LIST_LOADER_VISIBLE_LISTS_FILTER = TaskLists.SYNC_ENABLED + "=1";

    /**
     * Projection into the task list.
     */
    private final static String[] TASK_LIST_PROJECTION = new String[] {
            TaskContract.TaskListColumns._ID, TaskContract.TaskListColumns.LIST_NAME,
            TaskContract.TaskListSyncColumns.ACCOUNT_TYPE, TaskContract.TaskListSyncColumns.ACCOUNT_NAME, TaskContract.TaskListColumns.LIST_COLOR };


    /**
     * This interface provides a convenient way to get column indices of {@link #TASK_LIST_PROJECTION} without any overhead.
     */
    private interface TASK_LIST_PROJECTION_VALUES
    {
        int id = 0;
        @SuppressWarnings("unused")
        int list_name = 1;
        @SuppressWarnings("unused")
        int account_type = 2;
        @SuppressWarnings("unused")
        int account_name = 3;
        @SuppressWarnings("unused")
        int list_color = 4;
    }


    public interface OnTextInputListener
    {
        void onTextInput(String inputText);
    }


    @Parameter(key = ARG_LIST_ID)
    private long mListId = -1;

    @Parameter(key = ARG_CONTENT)
    private ContentSet mInitialContent;

    @Retain(permanent = true, key = "quick_add_list_id", classNS = "")
    private long mSelectedListId = -1;

    @Retain
    private int mLastColor = Color.WHITE;

    @Retain(permanent = true, key = "quick_add_save_count", classNS = "")
    private int mSaveCounter = 0;

    private View mColorBackground;
    private Spinner mListSpinner;

    private EditText mEditText;
    private View mContent;

    private View mSaveButton;
    private View mSaveAndNextButton;

    private TasksListCursorSpinnerAdapter mTaskListAdapter;

    private String mAuthority;


    /**
     * Create a {@link QuickAddDialogFragment} with the given title and initial text value.
     *
     * @return A new {@link QuickAddDialogFragment}.
     */
    public static QuickAddDialogFragment newInstance(long listId)
    {
        QuickAddDialogFragment fragment = new QuickAddDialogFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_LIST_ID, listId);
        fragment.setArguments(args);
        return fragment;
    }


    /**
     * Create a {@link QuickAddDialogFragment} with the given title and initial text value.
     *
     * @return A new {@link QuickAddDialogFragment}.
     */
    public static QuickAddDialogFragment newInstance(ContentSet content)
    {
        QuickAddDialogFragment fragment = new QuickAddDialogFragment();
        Bundle args = new Bundle();
        args.putParcelable(ARG_CONTENT, content);
        args.putLong(ARG_LIST_ID, -1);
        fragment.setArguments(args);
        return fragment;
    }


    public QuickAddDialogFragment()
    {
    }


    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState)
    {
        Dialog dialog = super.onCreateDialog(savedInstanceState);

        // hide the actual dialog title, we have our own...
        dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {

        // create ContextThemeWrapper from the original Activity Context with the custom theme
        final Context contextThemeWrapperLight = new ContextThemeWrapper(getActivity(), R.style.ThemeOverlay_AppCompat_Light);
        final Context contextThemeWrapperDark = new ContextThemeWrapper(getActivity(), R.style.Base_Theme_AppCompat);

        LayoutInflater localInflater = inflater.cloneInContext(contextThemeWrapperLight);
        View view = localInflater.inflate(R.layout.fragment_quick_add_dialog, container);

        ViewGroup headerContainer = (ViewGroup) view.findViewById(R.id.header_container);
        localInflater = inflater.cloneInContext(contextThemeWrapperDark);
        localInflater.inflate(R.layout.fragment_quick_add_dialog_header, headerContainer);

        if (savedInstanceState == null)
        {
            if (mListId >= 0)
            {
                mSelectedListId = mListId;
            }
        }

        mColorBackground = view.findViewById(R.id.color_background);
        mColorBackground.setBackgroundColor(mLastColor);

        mListSpinner = (Spinner) view.findViewById(R.id.task_list_spinner);
        mTaskListAdapter = new TasksListCursorSpinnerAdapter(getActivity(), R.layout.list_spinner_item_selected_quick_add, R.layout.list_spinner_item_dropdown);
        mListSpinner.setAdapter(mTaskListAdapter);
        mListSpinner.setOnItemSelectedListener(this);

        mEditText = (EditText) view.findViewById(android.R.id.input);
        mEditText.requestFocus();
        getDialog().getWindow().setSoftInputMode(LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        mEditText.setOnEditorActionListener(this);
        mEditText.addTextChangedListener(this);

        mContent = view.findViewById(R.id.content);

        mSaveButton = view.findViewById(android.R.id.button1);
        mSaveButton.setOnClickListener(this);
        mSaveAndNextButton = view.findViewById(android.R.id.button2);
        mSaveAndNextButton.setOnClickListener(this);
        view.findViewById(android.R.id.edit).setOnClickListener(this);

        mAuthority = AuthorityUtil.taskAuthority(getActivity());

        afterTextChanged(mEditText.getEditableText());

        setListUri(TaskLists.getContentUri(mAuthority), LIST_LOADER_VISIBLE_LISTS_FILTER);

        return view;
    }


    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle bundle)
    {
        return new CursorLoader(getActivity(), bundle.getParcelable(LIST_LOADER_URI), TASK_LIST_PROJECTION, bundle.getString(LIST_LOADER_FILTER), null,
                null);
    }


    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor cursor)
    {
        mTaskListAdapter.changeCursor(cursor);
        if (cursor != null)
        {
            if (mSelectedListId == -1)
            {
                mSelectedListId = mListId;
            }
            // set the list that was used the last time the user created an event
            // iterate over all lists and select the one that matches the given id
            cursor.moveToFirst();
            while (!cursor.isAfterLast())
            {
                long listId = cursor.getLong(TASK_LIST_PROJECTION_VALUES.id);
                if (listId == mSelectedListId)
                {
                    mListSpinner.setSelection(cursor.getPosition());
                    break;
                }
                cursor.moveToNext();
            }
        }
    }


    @Override
    public void onLoaderReset(Loader<Cursor> loader)
    {
        mTaskListAdapter.changeCursor(null);
    }


    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event)
    {
        if (EditorInfo.IME_ACTION_DONE == actionId)
        {
            createTask();
            dismiss();
            return true;
        }
        return false;
    }


    private void setListUri(Uri uri, String filter)
    {
        if (this.isAdded())
        {
            Bundle bundle = new Bundle();
            bundle.putParcelable(LIST_LOADER_URI, uri);
            bundle.putString(LIST_LOADER_FILTER, filter);

            getLoaderManager().restartLoader(-2, bundle, this);
        }
    }


    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
    {
        Cursor c = (Cursor) parent.getItemAtPosition(position);
        mLastColor = TaskFieldAdapters.LIST_COLOR.get(c);
        mColorBackground.setBackgroundColor(mLastColor);
        mSelectedListId = id;
    }


    @Override
    public void onNothingSelected(AdapterView<?> parent)
    {
    }


    /**
     * Launch the task editor activity.
     */
    private void editTask()
    {
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setData(Tasks.getContentUri(mAuthority));
        Bundle extraBundle = new Bundle();
        extraBundle.putParcelable(EditTaskActivity.EXTRA_DATA_CONTENT_SET, buildContentSet());
        intent.putExtra(EditTaskActivity.EXTRA_DATA_BUNDLE, extraBundle);
        getActivity().startActivity(intent);
    }


    /**
     * Store the task.
     */
    private void createTask()
    {
        ContentSet content = buildContentSet();
        RecentlyUsedLists.use(getContext(), content.getAsLong(Tasks.LIST_ID)); // update recently used lists
        content.persist(getActivity());
    }


    private ContentSet buildContentSet()
    {
        ContentSet task;
        if (mInitialContent != null)
        {
            task = new ContentSet(mInitialContent);
        }
        else
        {
            // add a new task on the tasks table
            task = new ContentSet(Tasks.getContentUri(mAuthority));
        }
        task.put(Tasks.LIST_ID, mListSpinner.getSelectedItemId());
        TaskFieldAdapters.TITLE.set(task, mEditText.getText().toString());
        return task;
    }


    @Override
    public void onClick(View v)
    {
        int id = v.getId();
        mSaveButton.setEnabled(false);
        mSaveAndNextButton.setEnabled(false);

        if (id == android.R.id.button1)
        {
            // "save" pressed
            createTask();
            dismiss();
        }
        else if (id == android.R.id.button2)
        {
            // "save and continue" pressed
            createTask();

            // reset text field
            mEditText.getText().clear();

            // bring the keyboard up again
            mEditText.requestFocus();
            InputMethodManager inputMethodManager = (InputMethodManager)
                getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            inputMethodManager.showSoftInput(mEditText, 0);
        }
        else if (id == android.R.id.edit)
        {
            // "edit" pressed
            editTask();
            dismiss();
        }
    }


    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after)
    {
    }


    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count)
    {
    }


    @Override
    public void afterTextChanged(Editable s)
    {
        // disable buttons if there is no title
        boolean enabled = s == null || s.length() != 0;
        mSaveButton.setEnabled(enabled);
        mSaveAndNextButton.setEnabled(enabled);
    }
}
