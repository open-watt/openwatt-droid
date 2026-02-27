package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.openwatt.droid.databinding.FragmentEnergyBinding

/**
 * Container fragment for the Energy tab.
 * Hosts a TabLayout + ViewPager2 with Summary and Flow sub-tabs.
 */
class EnergyFragment : Fragment() {
    private var _binding: FragmentEnergyBinding? = null
    private val binding get() = _binding!!

    private var serverId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentEnergyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sid = serverId ?: return

        binding.energyPager.adapter = EnergyPagerAdapter(this, sid)

        TabLayoutMediator(binding.energyTabs, binding.energyPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Summary"
                1 -> "Flow"
                else -> ""
            }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class EnergyPagerAdapter(
        fragment: Fragment,
        private val serverId: String,
    ) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> EnergySummaryFragment.newInstance(serverId)
                1 -> EnergyFlowFragment.newInstance(serverId)
                else -> throw IllegalStateException("Invalid position: $position")
            }
        }
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"

        fun newInstance(serverId: String): EnergyFragment {
            return EnergyFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
