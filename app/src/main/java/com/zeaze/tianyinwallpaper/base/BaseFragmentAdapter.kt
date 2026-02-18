package com.zeaze.tianyinwallpaper.base

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter

class BaseFragmentAdapter(
    fm: FragmentManager,
    private val titles: List<String>,
    private val fragments: List<BaseFragment>
) : FragmentPagerAdapter(fm) {
    override fun getPageTitle(position: Int): CharSequence {
        return titles[position]
    }

    override fun getItem(position: Int): Fragment {
        return fragments[position]
    }

    override fun getCount(): Int {
        return titles.size
    }
}
