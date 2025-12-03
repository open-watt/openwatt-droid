package com.openwatt.droid.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.openwatt.droid.R
import com.openwatt.droid.databinding.FragmentHomeBinding
import com.openwatt.droid.viewmodel.DashboardViewModel

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by activityViewModels()
    private var serverId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = arguments?.getString(ARG_SERVER_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ViewModel is shared with Activity, so it's already initialized
        observeViewModel()
    }

    private fun observeViewModel() {
        // No UI elements to update - status dot in toolbar provides all feedback
        // Future: Add observers here for Quick Stats and Favorites when implemented
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't stop polling - Activity ViewModel manages lifecycle
        _binding = null
    }

    companion object {
        private const val ARG_SERVER_ID = "server_id"

        fun newInstance(serverId: String): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVER_ID, serverId)
                }
            }
        }
    }
}
