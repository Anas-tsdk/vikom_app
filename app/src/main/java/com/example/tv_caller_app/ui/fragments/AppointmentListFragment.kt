package com.example.tv_caller_app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.data.datasource.AppointmentResponse
import com.example.tv_caller_app.ui.adapters.DayGroupedAppointmentAdapter
import com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModel
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModelFactory
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.util.showDeclineOptions
import java.time.LocalDateTime

class AppointmentListFragment : Fragment() {
    private val viewModel: AppointmentsViewModel by activityViewModels {
        AppointmentsViewModelFactory(requireActivity().application)
    }

    private lateinit var list: RecyclerView
    private lateinit var stateText: TextView
    private lateinit var retry: TextView
    private var content: AppointmentsUiState.Content? = null
    private var responseInProgressId: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_appointment_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.appointments_list)
        stateText = view.findViewById(R.id.txt_state)
        retry = view.findViewById(R.id.btn_retry)
        view.findViewById<TextView>(R.id.txt_screen_title).setText(R.string.appointments_section_upcoming)
        view.findViewById<View>(R.id.btn_screen_back).setOnClickListener {
            (activity as? MainActivity)?.showHome()
        }
        retry.setOnClickListener { viewModel.load() }
        list.layoutManager = LinearLayoutManager(requireContext())

        viewModel.uiState.observe(viewLifecycleOwner) {
            if (it is AppointmentsUiState.Content) content = it
            render(it)
        }
        viewModel.responseInProgress.observe(viewLifecycleOwner) {
            responseInProgressId = it
            content?.let(::showContent)
        }
        viewModel.responseError.observe(viewLifecycleOwner) { message ->
            if (!message.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(R.string.appointment_response_error, message), Toast.LENGTH_LONG).show()
                viewModel.clearResponseError()
            }
        }
        list.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        viewModel.load(forceRefresh = true)
    }

    private fun render(state: AppointmentsUiState) = when (state) {
        is AppointmentsUiState.Loading -> showMessage(R.string.appointments_loading, false)
        is AppointmentsUiState.Empty -> showMessage(R.string.appointments_empty, false)
        is AppointmentsUiState.NotLinked -> showMessage(R.string.appointments_not_linked, false)
        is AppointmentsUiState.NotConfigured -> showMessage(R.string.appointments_not_configured, false)
        is AppointmentsUiState.SessionExpired -> showMessage(R.string.appointments_session_expired, true)
        is AppointmentsUiState.Error -> showMessage(R.string.appointments_error, true)
        is AppointmentsUiState.Content -> showContent(state)
    }

    private fun showContent(state: AppointmentsUiState.Content) {
        stateText.visibility = View.GONE
        retry.visibility = View.GONE
        list.visibility = View.VISIBLE
        list.adapter = DayGroupedAppointmentAdapter(
            DayGroupedAppointmentAdapter.build(state.appointments, LocalDateTime.now()),
            responseInProgressId,
            ::respond
        )
    }

    private fun respond(appointment: com.example.tv_caller_app.domain.Appointment, response: AppointmentResponse) {
        if (response == AppointmentResponse.DECLINED) {
            showDeclineOptions(requireContext(), appointment) { proposal ->
                viewModel.respond(appointment.id, response, proposal)
            }
        } else {
            viewModel.respond(appointment.id, response)
        }
    }

    private fun showMessage(message: Int, allowRetry: Boolean) {
        list.visibility = View.GONE
        stateText.visibility = View.VISIBLE
        stateText.setText(message)
        retry.visibility = if (allowRetry) View.VISIBLE else View.GONE
    }
}
