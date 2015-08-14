package org.akvo.flow.ui.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.astuetz.PagerSlidingTabStrip;

import org.akvo.flow.R;
import org.akvo.flow.activity.SurveyActivity;
import org.akvo.flow.dao.SurveyDbAdapter;
import org.akvo.flow.domain.SurveyGroup;
import org.akvo.flow.service.SurveyedLocaleSyncService;

public class DatapointsFragment extends Fragment {
    private static final String TAG = DatapointsFragment.class.getSimpleName();

    private static final int POSITION_LIST = 0;
    private static final int POSITION_MAP = 1;

    private SurveyDbAdapter mDatabase;
    private TabsAdapter mTabsAdapter;
    private ViewPager mPager;

    private String[] mTabs;
    private SurveyGroup mSurveyGroup;

    public static DatapointsFragment instantiate(SurveyGroup surveyGroup) {
        DatapointsFragment fragment = new DatapointsFragment();
        Bundle args = new Bundle();
        args.putSerializable(SurveyActivity.EXTRA_SURVEY_GROUP, surveyGroup);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSurveyGroup = (SurveyGroup) getArguments().getSerializable(SurveyActivity.EXTRA_SURVEY_GROUP);
        mTabs = getResources().getStringArray(R.array.records_activity_tabs);
        mDatabase = new SurveyDbAdapter(getActivity());
        mDatabase.open();
        setHasOptionsMenu(true);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDatabase.close();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Delete empty Records, if any
        // TODO: For a more efficient cleanup, attempt to wipe ONLY the latest Record,
        // TODO: providing the id to RecordActivity, and reading it back on onActivityResult(...)
        mDatabase.deleteEmptyRecords();

        getActivity().registerReceiver(mSurveyedLocalesSyncReceiver,
                new IntentFilter(getString(R.string.action_locales_sync)));
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mSurveyedLocalesSyncReceiver);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.datapoints_fragment, container, false);
        mPager = (ViewPager)v.findViewById(R.id.pager);
        PagerSlidingTabStrip tabs = (PagerSlidingTabStrip) v.findViewById(R.id.tabs);

        // Init tabs
        mTabsAdapter = new TabsAdapter(getFragmentManager());
        mPager.setAdapter(mTabsAdapter);
        tabs.setViewPager(mPager);
        //tabs.setOnPageChangeListener(mTabsAdapter);

        return v;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.datapoints_fragment, menu);
        if (!mSurveyGroup.isMonitored()) {
            menu.removeItem(R.id.sync_records);
        }

        // "Order By" is only available for the ListFragment, not the MapFragment.
        // The navigation components maintain 2 different indexes: Tab index and Pager index.
        // The system seems to always update the tab index first, prior to the onCreateOptionsMenu
        // call (either selecting the Tab or swiping the Pager). For this reason, we need to check
        // the Tab index, not the Pager one, which turns out to be buggy in some Android versions.
        // TODO: If this approach is still unreliable, we'll need to invalidate the menu twice.
        if (mPager.getCurrentItem() == POSITION_MAP) {
            menu.removeItem(R.id.order_by);
        }
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.new_datapoint:
                String newLocaleId = mDatabase.createSurveyedLocale(mSurveyGroup.getId());
                ((SurveyActivity)getActivity()).onRecordSelected(newLocaleId);// TODO: Use interface pattern
                return true;
            case R.id.search:
                return getActivity().onSearchRequested();
            case R.id.sync_records:
                Toast.makeText(getActivity(), R.string.syncing_records, Toast.LENGTH_SHORT).show();
                Intent intent = new Intent(getActivity(), SurveyedLocaleSyncService.class);
                intent.putExtra(SurveyedLocaleSyncService.SURVEY_GROUP, mSurveyGroup.getId());
                getActivity().startService(intent);
                return true;
            case R.id.stats:
                StatsDialogFragment dialogFragment = StatsDialogFragment.newInstance(mSurveyGroup.getId());
                dialogFragment.show(getFragmentManager(), "stats");
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    class TabsAdapter extends FragmentPagerAdapter {

        public TabsAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public int getCount() {
            return mTabs.length;
        }

        private Fragment getFragment(int pos) {
            // Hell of a hack. This should be changed for a more reliable method
            String tag = "android:switcher:" + R.id.pager + ":" + pos;
            return getFragmentManager().findFragmentByTag(tag);
        }

        public void refreshFragments() {
            SurveyedLocaleListFragment listFragment = (SurveyedLocaleListFragment) getFragment(POSITION_LIST);
            MapFragment mapFragment = (MapFragment) getFragment(POSITION_MAP);

            if (listFragment != null) {
                listFragment.refresh(mSurveyGroup.getId());
            }
            if (mapFragment != null) {
                mapFragment.refresh(mSurveyGroup.getId());
            }
        }

        @Override
        public Fragment getItem(int position) {
            if (position == POSITION_LIST) {
                return SurveyedLocaleListFragment.instantiate(mSurveyGroup.getId());
            }
            // Map mode
            return MapFragment.instantiate(mSurveyGroup.getId(), null);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return mTabs[position];
        }

    }

    public void refresh(SurveyGroup surveyGroup) {
        mSurveyGroup = surveyGroup;
        mTabsAdapter.refreshFragments();
    }

    /**
     * BroadcastReceiver to notify of records synchronisation. This should be
     * fired from SurveyedLocalesSyncService.
     */
    private BroadcastReceiver mSurveyedLocalesSyncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "New Records have been synchronised. Refreshing fragments...");
            refresh(mSurveyGroup);
        }
    };

}
