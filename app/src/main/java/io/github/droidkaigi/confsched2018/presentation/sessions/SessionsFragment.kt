package io.github.droidkaigi.confsched2018.presentation.sessions

import android.app.Activity
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.view.PagerAdapter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.google.firebase.analytics.FirebaseAnalytics
import io.github.droidkaigi.confsched2018.R
import io.github.droidkaigi.confsched2018.databinding.FragmentSessionsBinding
import io.github.droidkaigi.confsched2018.di.Injectable
import io.github.droidkaigi.confsched2018.model.Room
import io.github.droidkaigi.confsched2018.model.SessionSchedule
import io.github.droidkaigi.confsched2018.presentation.FragmentStateNullablePagerAdapter
import io.github.droidkaigi.confsched2018.presentation.MainActivity
import io.github.droidkaigi.confsched2018.presentation.MainActivity.BottomNavigationItem.OnReselectedListener
import io.github.droidkaigi.confsched2018.presentation.Result
import io.github.droidkaigi.confsched2018.presentation.common.fragment.Findable
import io.github.droidkaigi.confsched2018.util.ProgressTimeLatch
import io.github.droidkaigi.confsched2018.util.ext.observe
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.properties.Delegates

class SessionsFragment : Fragment(), Injectable, Findable, OnReselectedListener {
    private lateinit var binding: FragmentSessionsBinding
    private lateinit var roomSessionsViewPagerAdapter: RoomSessionsViewPagerAdapter
    private lateinit var scheduleSessionsViewPagerAdapter: ScheduleSessionsViewPagerAdapter
    private lateinit var sessionsViewModel: SessionsViewModel
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.sessions, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        val roomTabMenu = menu.findItem(R.id.room_tab_mode)
        val scheduleTabMenu = menu.findItem(R.id.schedule_tab_mode)

        Timber.d("onPrepareOptionsMenu")

        when (sessionsViewModel.tabMode) {
            is SessionTabMode.ScheduleTabMode -> {
                roomTabMenu.isVisible = false
                scheduleTabMenu.isVisible = true
                scheduleTabMenu.isEnabled = true
            }
            is SessionTabMode.RoomTabMode -> {
                scheduleTabMenu.isVisible = false
                roomTabMenu.isVisible = true
                roomTabMenu.isEnabled = true
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.room_tab_mode -> true.apply {
                item.isEnabled = false
                sessionsViewModel.changeTabMode(SessionTabMode.ScheduleTabMode)
            }
            R.id.schedule_tab_mode -> true.apply {
                item.isEnabled = false
                sessionsViewModel.changeTabMode(SessionTabMode.RoomTabMode)
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        binding = FragmentSessionsBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        roomSessionsViewPagerAdapter = RoomSessionsViewPagerAdapter(childFragmentManager, activity!!)
        scheduleSessionsViewPagerAdapter = ScheduleSessionsViewPagerAdapter(childFragmentManager,
                activity!!)
        binding.sessionsViewPager.adapter = roomSessionsViewPagerAdapter

        sessionsViewModel = ViewModelProviders
                .of(this, viewModelFactory)
                .get(SessionsViewModel::class.java)

        val progressTimeLatch = ProgressTimeLatch {
            binding.progress.visibility = if (it) View.VISIBLE else View.GONE
        }
        sessionsViewModel.isLoading.observe(this, { isLoading ->
            progressTimeLatch.loading = isLoading ?: false
        })

        sessionsViewModel.rooms.observe(this, { result ->
            when (result) {
                is Result.Success -> {
                    roomSessionsViewPagerAdapter.setRooms(result.data)
                    activity?.invalidateOptionsMenu()
                }
                is Result.Failure -> {
                    Timber.e(result.e)
                }
            }
        })
        sessionsViewModel.schedules.observe(this) { result ->
            when (result) {
                is Result.Success -> {
                    scheduleSessionsViewPagerAdapter.setSchedules(result.data)
                    activity?.invalidateOptionsMenu()
                }
                is Result.Failure -> {
                    Timber.e(result.e)
                }
            }
        }

        sessionsViewModel.refreshResult.observe(this) { result ->
            when (result) {
                is Result.Failure -> {
                    // If user is offline, not error. So we write log to debug
                    Timber.d(result.e)
                    Snackbar.make(view, R.string.session_fetch_failed, Snackbar.LENGTH_LONG).apply {
                        setAction(R.string.session_load_retry) {
                            sessionsViewModel.onRetrySessions()
                        }
                    }.show()
                }
            }
        }
        sessionsViewModel.tabModeLiveData.observe(this) { tabMode ->
            when (tabMode) {
                SessionTabMode.RoomTabMode -> binding.sessionsViewPager.adapter = roomSessionsViewPagerAdapter
                SessionTabMode.ScheduleTabMode -> binding.sessionsViewPager.adapter = scheduleSessionsViewPagerAdapter
            }
        }

        lifecycle.addObserver(sessionsViewModel)

        binding.tabLayout.setupWithViewPager(binding.sessionsViewPager)
    }

    override fun onReselected() {
        when (sessionsViewModel.tabMode) {
            is SessionTabMode.RoomTabMode -> {
                val currentItem = binding.sessionsViewPager.currentItem
                val fragment = roomSessionsViewPagerAdapter
                        .instantiateItem(binding.sessionsViewPager, currentItem)

                if (fragment is CurrentSessionScroller) {
                    fragment.scrollToCurrentSession()
                }
            }
            is SessionTabMode.ScheduleTabMode -> {
                val now = Date(ZonedDateTime.now(ZoneId.of(ZoneId.SHORT_IDS["JST"]))
                        .toInstant().toEpochMilli())
                val position = scheduleSessionsViewPagerAdapter.getRecentScheduleTabPosition(now)
                binding.sessionsViewPager.currentItem = position
            }
        }
    }

    override val tagForFinding = MainActivity.BottomNavigationItem.SESSION.name

    interface CurrentSessionScroller {
        fun scrollToCurrentSession()
    }

    companion object {
        fun newInstance(): SessionsFragment = SessionsFragment()
    }
}

class RoomSessionsViewPagerAdapter(
        fragmentManager: FragmentManager,
        override val activity: Activity
) : FragmentStateNullablePagerAdapter(fragmentManager), SessionsViewPagerAdapter {
    var roomTabs = mutableListOf<SessionsViewPagerAdapter.Tab.RoomTab>()
    override val tabs = arrayListOf<SessionsViewPagerAdapter.Tab>()
    override val fireBaseAnalytics = FirebaseAnalytics.getInstance(activity)
    override var currentTab by Delegates.observable<SessionsViewPagerAdapter.Tab?>(null) { _, old, new ->
        onChangeCurrentTab(old, new)
    }

    override fun maySetupTabs(otherTabs: List<SessionsViewPagerAdapter.Tab>) {
        super<SessionsViewPagerAdapter>.maySetupTabs(otherTabs)
        notifyDataSetChanged()
    }

    override fun getPageTitle(position: Int): CharSequence {
        return super<SessionsViewPagerAdapter>.getPageTitle(position)
    }

    override fun getItem(position: Int): Fragment {
        return super<SessionsViewPagerAdapter>.getItem(position)
    }

    override fun getItemPosition(`object`: Any): Int {
        return PagerAdapter.POSITION_NONE
    }

    override fun getCount(): Int = tabs.size

    override fun setPrimaryItem(container: ViewGroup, position: Int, o: Any?) {
        currentTab = tabs.getOrNull(position)
    }

    fun setRooms(rooms: List<Room>) {
        if (rooms != roomTabs.map { it.room }) {
            roomTabs = rooms.map {
                SessionsViewPagerAdapter.Tab.RoomTab(it)
            }.toMutableList()
        }

        maySetupTabs(roomTabs)
    }
}

class ScheduleSessionsViewPagerAdapter(
        fragmentManager: FragmentManager,
        override val activity: Activity
) : FragmentStateNullablePagerAdapter(fragmentManager), SessionsViewPagerAdapter {

    var schedulesTabs = mutableListOf<SessionsViewPagerAdapter.Tab.TimeTab>()
    override val tabs = arrayListOf<SessionsViewPagerAdapter.Tab>()
    override val fireBaseAnalytics = FirebaseAnalytics.getInstance(activity)

    override var currentTab by Delegates.observable<SessionsViewPagerAdapter.Tab?>(null) { _, old, new ->
        onChangeCurrentTab(old, new)
    }

    override fun maySetupTabs(otherTabs: List<SessionsViewPagerAdapter.Tab>) {
        super<SessionsViewPagerAdapter>.maySetupTabs(otherTabs)
        notifyDataSetChanged()
    }

    override fun getPageTitle(position: Int): CharSequence {
        return super<SessionsViewPagerAdapter>.getPageTitle(position)
    }

    override fun getItem(position: Int): Fragment {
        return super<SessionsViewPagerAdapter>.getItem(position)
    }


    override fun getItemPosition(`object`: Any): Int {
        return super<SessionsViewPagerAdapter>.getItemPosition(`object`)
    }

    override fun getCount(): Int = super<SessionsViewPagerAdapter>.getCount()

    override fun setPrimaryItem(container: ViewGroup, position: Int, o: Any?) {
        super<FragmentStateNullablePagerAdapter>.setPrimaryItem(container, position, o)
        super<SessionsViewPagerAdapter>.setPrimaryItem(container, position, o)
    }

    fun getRecentScheduleTabPosition(time: Date): Int {
        val position = schedulesTabs.withIndex().firstOrNull {
            it.value.schedule.startTime > time
        }?.index?.dec() ?: 0

        return position + 1
    }

    fun setSchedules(schedules: List<SessionSchedule>) {
        if (schedules != schedulesTabs.map { it.schedule }) {
            schedulesTabs = schedules.map {
                SessionsViewPagerAdapter.Tab.TimeTab(it)
            }.toMutableList()
        }

        maySetupTabs(schedulesTabs)
    }
}

interface SessionsViewPagerAdapter {
    val tabs: MutableList<Tab>
    val fireBaseAnalytics: FirebaseAnalytics
    val activity: Activity

    sealed class Tab(val title: String) {
        object All : Tab("All")
        data class RoomTab(val room: Room) : Tab(room.name)
        data class TimeTab(val schedule: SessionSchedule) :
                Tab("Day${schedule.dayNumber} / ${startDateFormat.format(schedule.startTime)}")
    }

    var currentTab: Tab?

    fun getItemPosition(`object`: Any): Int {
        return PagerAdapter.POSITION_NONE
    }

    fun getCount(): Int = tabs.size

    fun setPrimaryItem(container: ViewGroup, position: Int, o: Any?) {
        currentTab = tabs.getOrNull(position)
    }

    fun maySetupTabs(otherTabs: List<SessionsViewPagerAdapter.Tab>) {
        if (tabs.isNotEmpty() && tabs.subList(1, tabs.size) == otherTabs) {
            return
        }

        tabs.clear()
        tabs.add(SessionsViewPagerAdapter.Tab.All)
        tabs.addAll(otherTabs)
    }

    fun getPageTitle(position: Int): CharSequence = tabs[position].title

    fun getItem(position: Int): Fragment {
        val tab = tabs[position]
        return when (tab) {
            SessionsViewPagerAdapter.Tab.All -> {
                AllSessionsFragment.newInstance()
            }
            is SessionsViewPagerAdapter.Tab.RoomTab -> {
                RoomSessionsFragment.newInstance(tab.room)
            }
            is SessionsViewPagerAdapter.Tab.TimeTab -> {
                ScheduleSessionsFragment.newInstance(tab.schedule)
            }
        }
    }

    fun onChangeCurrentTab(old: SessionsViewPagerAdapter.Tab?, new: SessionsViewPagerAdapter.Tab?) {
        if (old != new && new != null) {
            val key = when (new) {
                SessionsViewPagerAdapter.Tab.All -> AllSessionsFragment::class.java.simpleName
                is SessionsViewPagerAdapter.Tab.RoomTab -> RoomSessionsFragment::class.java.simpleName + new.room.name
                is SessionsViewPagerAdapter.Tab.TimeTab -> ScheduleSessionsFragment::class.java.simpleName + new.title
            }
            fireBaseAnalytics.setCurrentScreen(activity, null, key)
        }
    }

    companion object {
        private val startDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
}
