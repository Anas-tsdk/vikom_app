package com.example.tv_caller_app.ui.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.repository.PatientProfileRepository
import com.example.tv_caller_app.domain.CareTeamMember
import com.example.tv_caller_app.ui.adapters.CareTeamAdapter
import com.example.tv_caller_app.ui.activities.MainActivity
import kotlinx.coroutines.launch

class CareTeamFragment : Fragment() {
    private lateinit var list: RecyclerView
    private lateinit var stateText: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_care_team, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.care_team_list)
        stateText = view.findViewById(R.id.txt_state)
        view.findViewById<TextView>(R.id.txt_screen_title).setText(R.string.care_team_title)
        view.findViewById<View>(R.id.btn_screen_back).setOnClickListener {
            (activity as? MainActivity)?.showHome()
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        load()
    }

    private fun load() {
        showMessage(R.string.appointments_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as TVCallerApplication
            runCatching {
                PatientProfileRepository.getInstance(
                    SessionManager.getInstance(app),
                    app.getAuthRepository()
                ).getMyCareTeam(forceRefresh = true)
            }.onSuccess(::render).onFailure {
                Log.w("CareTeamFragment", "Could not load care team", it)
                showMessage(R.string.care_team_error)
            }
        }
    }

    private fun render(team: List<CareTeamMember>) {
        if (team.isEmpty()) return showMessage(R.string.care_team_empty)
        stateText.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.adapter = CareTeamAdapter(team)
    }

    private fun showMessage(message: Int) {
        list.visibility = View.GONE
        stateText.visibility = View.VISIBLE
        stateText.setText(message)
    }
}
