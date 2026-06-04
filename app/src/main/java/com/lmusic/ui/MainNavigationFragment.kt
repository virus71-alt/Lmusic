package com.lmusic.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lmusic.R
import com.lmusic.databinding.FragmentMainNavigationBinding
import com.lmusic.player.MiniPlayerController

/**
 * Premium-Doodle navigation host with a 4-tab bottom bar — every tab is its own
 * real fragment:
 *
 *   Search  → ExploreFragment         (YouTube search + genre chips, default tab)
 *   Library → DownloadHistoryFragment (downloaded tracks)
 *   Favs    → FavoritesFragment       (hearted tracks)
 *   Profile → ProfileFragment         (stats · settings links)
 *
 * Tabs are kept alive via show()/hide() so their state survives tab switches.
 * A single global [MiniPlayerController] lives here so only ONE controller binds
 * to the playback service at a time.
 */
class MainNavigationFragment : Fragment() {

    private var _binding: FragmentMainNavigationBinding? = null
    private val binding get() = _binding!!

    // ── Lazy fragment creation ──────────────────────────────────────────────
    // Library is the default landing tab, eagerly instantiated.  Search is
    // also eager since it owns the search EditText (warming the IME path).
    // Favs + Profile are deferred to first visit.
    private val libraryFragment = DownloadHistoryFragment()
    private val exploreFragment = ExploreFragment()

    private val favoritesFragment by lazy(LazyThreadSafetyMode.NONE) { FavoritesFragment() }
    private val profileFragment   by lazy(LazyThreadSafetyMode.NONE) { ProfileFragment() }

    /** Tracks which lazy fragments have already been attached to the host. */
    private val attached = mutableSetOf<Dest>()

    private var _miniPlayer: MiniPlayerController? = null
    val globalMiniPlayer: MiniPlayerController? get() = _miniPlayer

    /**
     * Bottom-bar destinations in display order: Library · Search · Favs · Profile.
     * Library is the default.
     */
    enum class Dest { LIBRARY, SEARCH, FAVS, PROFILE }
    private var activeDest = Dest.LIBRARY

    private data class NavItem(val root: View, val icon: ImageView, val label: TextView, val dest: Dest)
    private val navItems = mutableListOf<NavItem>()

    /**
     * Returns the Fragment to use for [dest].  CRITICAL: consult the child
     * FragmentManager first so that fragments restored from saved state are
     * reused, rather than the brand-new instance from our `libraryFragment`
     * / `exploreFragment` / `by lazy` fields.  Failing to do this causes
     * "Fragment already added" crashes when the activity is recreated
     * (rotation, returning from system settings, etc.).
     */
    private fun fragmentFor(dest: Dest): Fragment {
        val tag = tagFor(dest)
        childFragmentManager.findFragmentByTag(tag)?.let { return it }
        return when (dest) {
            Dest.LIBRARY -> libraryFragment
            Dest.SEARCH  -> exploreFragment
            Dest.FAVS    -> favoritesFragment
            Dest.PROFILE -> profileFragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainNavigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Global mini-player
        _miniPlayer = MiniPlayerController(requireContext()).apply {
            bind(binding.globalMiniPlayer)
            setOnCardClick {
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_up, R.anim.fade_out,
                                         R.anim.fade_in, R.anim.slide_down)
                    .replace(R.id.fragment_container, NowPlayingFragment())
                    .addToBackStack("nowplaying")
                    .commit()
            }
        }

        binding.btnGlobalSettings.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up, R.anim.fade_out,
                                     R.anim.fade_in, R.anim.slide_down)
                .replace(R.id.fragment_container, SettingsHostFragment())
                .addToBackStack("settings")
                .commit()
        }

        // Decide what's already attached BEFORE issuing any transaction.
        attached.clear()
        for (d in Dest.entries) {
            if (childFragmentManager.findFragmentByTag(tagFor(d)) != null) {
                attached += d
            }
        }

        // ── Single initial transaction ──────────────────────────────────────
        // One commit that adds whatever's missing AND positions show/hide for
        // the active tab.  No animations on this initial pass — the previous
        // approach (commitNow then a second animated commit) left the visible
        // fragment stuck at alpha=0 on some devices, which is exactly what
        // produced the "Library blank on first launch" symptom.
        val tx = childFragmentManager.beginTransaction()

        if (Dest.LIBRARY !in attached) {
            tx.add(R.id.nav_content, libraryFragment, "library")
            attached += Dest.LIBRARY
        }
        if (Dest.SEARCH !in attached) {
            tx.add(R.id.nav_content, exploreFragment, "search")
            attached += Dest.SEARCH
        }

        // Visibility — show only the destination we want, hide everything
        // else.  This covers both the "fresh add" case AND the "saved-state
        // restore" case in one pass.
        for (d in attached) {
            val f = fragmentFor(d)
            if (d == activeDest) tx.show(f) else tx.hide(f)
        }

        // commitNow → synchronous, so the views are real before we paint the
        // bottom-nav style.  No animations are queued, so nothing can leave a
        // fragment stuck at alpha=0.
        tx.commitNow()

        // Force a fresh paint of the bottom-nav pill.
        lastStyledDest = null
        buildBottomNav()
        styleNav(activeDest)
    }

    // ── Bottom navigation ─────────────────────────────────────────────────

    private fun buildBottomNav() {
        val bar = binding.bottomNav
        bar.removeAllViews()
        navItems.clear()
        val inflater = LayoutInflater.from(requireContext())

        val defs = listOf(
            Triple(Dest.LIBRARY, "Library", R.drawable.ic_library),
            Triple(Dest.SEARCH,  "Search",  R.drawable.ic_search),
            Triple(Dest.FAVS,    "Favs",    R.drawable.ic_favorite),
            Triple(Dest.PROFILE, "Profile", R.drawable.ic_person)
        )

        for ((dest, label, iconRes) in defs) {
            val item = inflater.inflate(R.layout.item_bottom_nav, bar, false)
            val icon = item.findViewById<ImageView>(R.id.nav_icon)
            val text = item.findViewById<TextView>(R.id.nav_label)
            icon.setImageResource(iconRes)
            text.text = label

            item.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            item.setOnClickListener { selectDest(dest) }

            bar.addView(item)
            navItems += NavItem(item, icon, text, dest)
        }
    }

    private fun selectDest(dest: Dest, initial: Boolean = false) {
        activeDest = dest
        styleNav(dest)

        val tx = childFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)

        // Lazy attach on first visit — only construct the fragment + inflate
        // its view when the user actually picks the tab.
        if (dest !in attached) {
            val tag = tagFor(dest)
            tx.add(R.id.nav_content, fragmentFor(dest), tag)
            attached += dest
        }

        // Show only the target; hide every other attached fragment.
        for (d in attached) {
            val f = fragmentFor(d)
            if (d == dest) tx.show(f) else tx.hide(f)
        }
        tx.commit()

        if (dest == Dest.SEARCH && !initial) exploreFragment.focusSearch()
    }

    private fun tagFor(d: Dest): String = when (d) {
        Dest.SEARCH  -> "search"
        Dest.LIBRARY -> "library"
        Dest.FAVS    -> "favs"
        Dest.PROFILE -> "profile"
    }

    /** Cached previously-active dest so we only animate the two changed items. */
    private var lastStyledDest: Dest? = null

    private fun styleNav(active: Dest) {
        if (lastStyledDest == active) return                 // no-op — nothing to do
        val previous = lastStyledDest
        lastStyledDest = active

        val ctx = requireContext()
        val onPeach  = ContextCompat.getColor(ctx, R.color.on_doodle_peach)
        val inactive = ContextCompat.getColor(ctx, R.color.text_tertiary)
        for (item in navItems) {
            val isActive  = item.dest == active
            val wasActive = item.dest == previous
            // Skip rows whose state didn't change — avoids 3 needless animations
            // on every tab tap.
            if (!isActive && !wasActive && previous != null) continue

            if (isActive) {
                item.root.setBackgroundResource(R.drawable.bg_nav_pill)
                item.icon.setColorFilter(onPeach)
                item.label.setTextColor(onPeach)
                item.root.animate().scaleX(1.06f).scaleY(1.06f).setDuration(160).start()
            } else {
                item.root.setBackgroundResource(android.R.color.transparent)
                item.icon.setColorFilter(inactive)
                item.label.setTextColor(inactive)
                item.root.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
        }
    }

    override fun onDestroyView() {
        _miniPlayer?.release()
        _miniPlayer = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Lets a child fragment reach the shared mini-player. */
        fun find(fragment: Fragment): MainNavigationFragment? {
            var current: Fragment? = fragment.parentFragment
            while (current != null) {
                if (current is MainNavigationFragment) return current
                current = current.parentFragment
            }
            return null
        }
    }
}
