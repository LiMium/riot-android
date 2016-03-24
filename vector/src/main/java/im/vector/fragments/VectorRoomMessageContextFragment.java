/*
 * Copyright 2015 OpenMarket Ltd
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

package im.vector.fragments;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.Toast;

import org.matrix.androidsdk.MXDataHandler;
import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.adapters.MessageRow;
import org.matrix.androidsdk.adapters.MessagesAdapter;
import org.matrix.androidsdk.data.EventTimeline;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.fragments.IconAndTextDialogFragment;
import org.matrix.androidsdk.listeners.IMXEventListener;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.Message;
import org.matrix.androidsdk.rest.model.User;
import org.matrix.androidsdk.util.EventDisplay;
import org.matrix.androidsdk.util.JsonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Timer;

import im.vector.Matrix;
import im.vector.R;
import im.vector.activity.VectorBaseSearchActivity;
import im.vector.activity.VectorUnifiedSearchActivity;
import im.vector.adapters.VectorMessagesAdapter;
import im.vector.adapters.VectorSearchMessagesListAdapter;

public class VectorRoomMessageContextFragment extends Fragment {
    private static String LOG_TAG = "RoomMessageContextFrag";

    public static final String ARG_ROOM_ID = "VectorRoomMessageContextFragment.ARG_ROOM_ID";
    public static final String ARG_MATRIX_ID = "VectorRoomMessageContextFragment.ARG_MATRIX_ID";
    public static final String ARG_LAYOUT_ID = "VectorRoomMessageContextFragment.ARG_LAYOUT_ID";
    public static final String ARG_EVENT_ID = "VectorRoomMessageContextFragment.ARG_EVENT_ID";

    /**
     * Listeners to manage UI items
     */
    public interface IContextEventsListener {
        /**
         * Show a spinner when a back pagination is started.
         */
        void showBackPaginationSpinner();

        /**
         * Hide a spinner when a back pagination is ended.
         */
        void hideBackPaginationSpinner();

        /**
         * Show a spinner when a foward pagination is started.
         */
        void showForwardPaginationSpinner();

        /**
         * Hide a spinner when a foward pagination is started.
         */
        void hideForwardPaginationSpinner();

        /**
         * Display a spinner when the global initialization is started.
         */
        void showGlobalInitpinner();

        /**
         * Hide a spinner when the global initialization is done.
         */
        void hideGlobalInitpinner();
    }

    // parameters
    private String mMatrixId;
    private MXSession mSession;
    private Room mRoom;
    private String mEventId;

    // the class which provides the backward / forward pagaintation methods
    private EventTimeline mEventTimeline;

    // initialization status
    private boolean mIsInitialized;

    // the messages listView
    private ListView mMessageListView;

    // the adapter
    private VectorMessagesAdapter mAdapter;

    // pagination statuses
    private boolean mIsBackPaginating;
    private boolean mIsFwdPaginating;

    // lock the pagination while refreshing the list view to avoid having twice or thrice refreshes sequence.
    private boolean mLockBackPagination;
    private boolean mLockFwdPagination;

    private IContextEventsListener mAppContextListener;

    private EventTimeline.EventTimelineListener mEventsListenener = new EventTimeline.EventTimelineListener() {
        @Override
        public void onEvent(Event event, EventTimeline.Direction direction, RoomState roomState) {
            if (direction == EventTimeline.Direction.FORWARDS) {
                if (Event.EVENT_TYPE_REDACTION.equals(event.type)) {
                    mAdapter.removeEventById(event.getRedacts());
                } else {
                    mAdapter.add(new MessageRow(event, roomState), false);
                }
            } else {
                mAdapter.addToFront(event, roomState);
            }
        }
    };

    // scroll events listener
    // use to detect when a backward / forward pagination must be started
    private AbsListView.OnScrollListener mScrollListener = new AbsListView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {
            //check only when the user scrolls the content
            if (scrollState == SCROLL_STATE_TOUCH_SCROLL) {
                int firstVisibleRow = mMessageListView.getFirstVisiblePosition();
                int lastVisibleRow = mMessageListView.getLastVisiblePosition();
                int count = mMessageListView.getCount();

                if ((lastVisibleRow + 10) >= count) {
                    forwardPaginate();
                }  else if (firstVisibleRow < 2) {
                    backwardPaginate();
                }
            }
        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            if (firstVisibleItem < 2) {
                backwardPaginate();
            } else if ((firstVisibleItem + visibleItemCount + 10) >= totalItemCount) {
                forwardPaginate();
            }
        }
    };

    /**
     * static constructor
     * @param matrixId the session Id.
     * @param layoutResId the used layout.
     * @return
     */
    public static VectorRoomMessageContextFragment newInstance(String matrixId, String roomId, String eventId, int layoutResId) {
        VectorRoomMessageContextFragment frag = new VectorRoomMessageContextFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_LAYOUT_ID, layoutResId);

        args.putString(ARG_MATRIX_ID, matrixId);
        args.putString(ARG_ROOM_ID, roomId);
        args.putString(ARG_EVENT_ID, eventId);

        frag.setArguments(args);
        return frag;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    @Override
    public void onAttach(Activity aHostActivity) {
        super.onAttach(aHostActivity);

        try {
            mAppContextListener = (IContextEventsListener)aHostActivity;
        } catch (Exception e) {
            Log.e(LOG_TAG, aHostActivity + " does not implement  IContextEventsListener");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        Bundle args = getArguments();

        mMatrixId = args.getString(ARG_MATRIX_ID);
        mSession = Matrix.getInstance(getActivity()).getSession(mMatrixId);

        if (null == mSession) {
            throw new RuntimeException("Must have valid default MXSession.");
        }


        String roomId = args.getString(ARG_ROOM_ID);

        if (!TextUtils.isEmpty(roomId)) {
            mRoom = mSession.getDataHandler().getRoom(roomId);
        }

        mEventId = args.getString(ARG_EVENT_ID);

        View v = inflater.inflate(args.getInt(ARG_LAYOUT_ID), container, false);
        mMessageListView = ((ListView)v.findViewById(org.matrix.androidsdk.R.id.listView_messages));

        int selectionIndex = -1;

        if (mAdapter == null) {
            // only init the adapter if it wasn't before, so we can preserve messages/position.
            mAdapter = new VectorMessagesAdapter(mSession, getActivity(), mSession.getMediasCache());

        } else if(null != savedInstanceState){
            if (savedInstanceState.containsKey("FIRST_VISIBLE_ROW")) {
                selectionIndex = savedInstanceState.getInt("FIRST_VISIBLE_ROW");
            }
            else {
                selectionIndex = -1;
            }
        }

        mMessageListView.setAdapter(mAdapter);

        if (-1 != selectionIndex) {
            final int fselectionIndex = selectionIndex;

            // fill the page
            mMessageListView.post(new Runnable() {
                @Override
                public void run() {
                    mMessageListView.setSelection(fselectionIndex);
                }
            });
        }

        if (null == mEventTimeline) {
            mEventTimeline = new EventTimeline(mSession.getDataHandler(), roomId, mEventId);
            mEventTimeline.addEventTimelineListener(mEventsListenener);
            initializeTimeline();
        }

        return v;
    }

    /**
     * Start a backward pagination
     */
    private void backwardPaginate() {
        if (mLockBackPagination) {
            Log.d(LOG_TAG, "The back pagination is locked.");
            return;
        }

        if (!mIsBackPaginating) {
            final int countBeforeUpdate = mAdapter.getCount();

            mIsBackPaginating = mEventTimeline.backPaginate(new ApiCallback<Integer>() {
                /**
                 * the back pagination is ended.
                 */
                private void onEndOfPagination(String errorMessage) {
                    if (null != errorMessage) {
                        Log.e(LOG_TAG, "backwardPaginate fails : " + errorMessage);
                    }

                    mIsBackPaginating = false;

                    if (null != mAppContextListener) {
                        mAppContextListener.hideBackPaginationSpinner();
                    }
                }

                @Override
                public void onSuccess(Integer info) {
                    // Scroll the list down to where it was before adding rows to the top
                    mMessageListView.post(new Runnable() {
                        @Override
                        public void run() {
                            mLockFwdPagination = true;

                            int countDiff = mAdapter.getCount() - countBeforeUpdate;
                            int expectedFirstPos = mMessageListView.getFirstVisiblePosition();

                            // check if some messages have been added
                            // do not refresh the UI if no message have been added
                            if (0 != countDiff) {
                                // refresh the list only at the end of the sync
                                // else the one by one message refresh gives a weird UX
                                // The application is almost frozen during the
                                mAdapter.notifyDataSetChanged();

                                // do not use count because some messages are not displayed
                                // so we compute the new pos
                                expectedFirstPos = mMessageListView.getFirstVisiblePosition() + countDiff;
                                mMessageListView.setSelection(expectedFirstPos);

                                final int fExpectedFirstPos = expectedFirstPos;

                                mMessageListView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        // ensure that the pos is the expected one
                                        // it sometimes fails to select the right one.
                                        if (Math.abs(fExpectedFirstPos - mMessageListView.getFirstVisiblePosition()) > 1) {
                                            Log.d(LOG_TAG, "Backscroll : first " + mMessageListView.getFirstVisiblePosition() + " instead of " + fExpectedFirstPos);
                                            mMessageListView.setSelection(fExpectedFirstPos);

                                            mMessageListView.post(new Runnable() {
                                                @Override
                                                public void run() {
                                                    onEndOfPagination(null);
                                                    mLockFwdPagination = false;
                                                }
                                            });
                                        } else {
                                            onEndOfPagination(null);
                                            mLockFwdPagination = false;
                                        }
                                    }
                                });
                            } else {
                                mLockFwdPagination = false;
                            }
                        }
                    });
                }

                @Override
                public void onNetworkError(Exception e) {
                    onEndOfPagination(e.getLocalizedMessage());
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    onEndOfPagination(e.getLocalizedMessage());
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    onEndOfPagination(e.getLocalizedMessage());
                }
            });

            if (mIsBackPaginating && (null != mAppContextListener)) {
                mAppContextListener.showBackPaginationSpinner();
            }
        }
    }

    /**
     * Start a forward pagination
     */
    private void forwardPaginate() {
        if (mLockFwdPagination) {
            Log.d(LOG_TAG, "The forward pagination is locked.");
            return;
        }

        if (!mIsFwdPaginating) {
            mIsFwdPaginating = mEventTimeline.forwardPaginate(new ApiCallback<Integer>() {
                /**
                 * the forward pagination is ended.
                 */
                private void onEndOfPagination(String errorMessage) {
                    if (null != errorMessage) {
                        Log.e(LOG_TAG, "forwardPaginate fails : " + errorMessage);
                    }

                    mIsFwdPaginating = false;

                    if (null != mAppContextListener) {
                        mAppContextListener.hideForwardPaginationSpinner();
                    }
                }

                @Override
                public void onSuccess(Integer info) {
                    final int firstPos = mMessageListView.getFirstVisiblePosition();

                    // Scroll the list down to where it was before adding rows to the top
                    mMessageListView.post(new Runnable() {
                        @Override
                        public void run() {
                            mLockBackPagination = true;

                            mMessageListView.setSelection(firstPos);
                            mAdapter.notifyDataSetChanged();

                            mMessageListView.post(new Runnable() {
                                @Override
                                public void run() {
                                    // check if the selected item is the right one
                                    // it sometimes fails
                                    if (Math.abs(firstPos - mMessageListView.getFirstVisiblePosition()) > 1) {
                                        mMessageListView.setSelection(firstPos);
                                        Log.d(LOG_TAG, "forward scroll : first " + mMessageListView.getFirstVisiblePosition() + " instead of " + firstPos);

                                        mMessageListView.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                onEndOfPagination(null);
                                                mLockBackPagination = false;
                                            }
                                        });
                                    } else {
                                        onEndOfPagination(null);
                                        mLockBackPagination = false;
                                    }
                                }
                            });
                        }
                    });
                }

                @Override
                public void onNetworkError(Exception e) {
                    onEndOfPagination(e.getLocalizedMessage());
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    onEndOfPagination(e.getLocalizedMessage());
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    onEndOfPagination(e.getLocalizedMessage());
                }
            });

            if (mIsFwdPaginating && (null != mAppContextListener)) {
                mAppContextListener.showForwardPaginationSpinner();
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (null != mEventTimeline) {
            mEventTimeline.removeEventTimelineListener(mEventsListenener);

            // cancel any pending request
            mEventTimeline.cancelPaginationRequest();
            mIsBackPaginating = false;
            mIsFwdPaginating = false;

            mLockBackPagination = true;
            mLockFwdPagination = true;

            if (null != mAppContextListener) {
                mAppContextListener.hideBackPaginationSpinner();
                mAppContextListener.hideForwardPaginationSpinner();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (mIsInitialized) {
            // plug the scroll events listener only when the rendering is done
            // to avoid unexpected pagination requests.
            mMessageListView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mLockBackPagination = false;
                    mLockFwdPagination = false;
                    mMessageListView.setOnScrollListener(mScrollListener);
                }
            }, 300);
        } else {
            mLockBackPagination = false;
            mLockFwdPagination = false;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (null != mMessageListView) {
            int selected = mMessageListView.getFirstVisiblePosition();

            // ListView always returns the previous index while filling from bottom
            if (selected > 0) {
                selected++;
            }

            outState.putInt("FIRST_VISIBLE_ROW", selected);
        }
    }

    //==============================================================================================================
    // Initialization methods
    //==============================================================================================================

    /**
     * The timeline fails to be initialized.
     * @param description the error description
     */
    private void onGlobalInitFailed(String description) {
        Log.e(LOG_TAG, "onGlobalInitFailed " + description);
        Toast.makeText(getActivity(), description, Toast.LENGTH_SHORT);
        getActivity().finish();
    }

    /**
     * Initialize the timeline to fill the screen
     */
    private void initializeTimeline() {
        if (null != mAppContextListener) {
            mAppContextListener.showGlobalInitpinner();
        }
        mEventTimeline.resetPaginationAroundInitialEvent(20 * 2, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                if (null != mAppContextListener) {
                    mAppContextListener.hideGlobalInitpinner();
                }

                mMessageListView.post(new Runnable() {
                    @Override
                    public void run() {
                        // search the event pos in the adapter
                        // some events are not displayed so the added events count cannot be used.
                        int eventPos = 0;
                        for (; eventPos < mAdapter.getCount(); eventPos++) {
                            if (TextUtils.equals(mAdapter.getItem(eventPos).getEvent().eventId, mEventId)) {
                                break;
                            }
                        }

                        View parentView = (View) mMessageListView.getParent();

                        mAdapter.notifyDataSetChanged();
                        // center the message in the
                        mMessageListView.setSelectionFromTop(eventPos, parentView.getHeight() / 2);

                        mMessageListView.post(new Runnable() {
                            @Override
                            public void run() {
                                mIsInitialized = true;
                                mMessageListView.setOnScrollListener(mScrollListener);
                            }
                        });
                    }
                });
            }

            @Override
            public void onNetworkError(Exception e) {
                onGlobalInitFailed(e.getLocalizedMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                onGlobalInitFailed(e.getLocalizedMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                onGlobalInitFailed(e.getLocalizedMessage());
            }
        });
    }
}
