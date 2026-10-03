package com.group41.kotlinapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.viewmodel.ProfileEvent
import com.group41.kotlinapp.viewmodel.ProfileUiState
import com.group41.kotlinapp.viewmodel.ProfileViewModel
import kotlinx.coroutines.launch

/**
 * Editar perfil: foto (galería o URL), nombre y descripción.
 *
 * Usa el ProfileViewModel de ProfileActivity: lo que cambia aquí se ve al
 * instante en el perfil, y una subida en curso no se corta al rotar.
 */
class EditProfileBottomSheet : BottomSheetDialogFragment() {

    /** El de la Activity, no uno propio: así el perfil y el editor comparten el usuario */
    private val viewModel: ProfileViewModel by lazy {
        ViewModelProvider(requireActivity())[ProfileViewModel::class.java]
    }

    /** Último usuario dibujado: la foto solo se recarga si cambió */
    private var shownUser: UserDto? = null

    private lateinit var avatarImage: ImageView
    private lateinit var avatarInitials: TextView
    private lateinit var avatarProgress: View
    private lateinit var uploadButton: View
    private lateinit var removePhoto: TextView
    private lateinit var photoUrl: EditText
    private lateinit var useUrl: View
    private lateinit var name: EditText
    private lateinit var bio: EditText
    private lateinit var save: View

    /** Selector de fotos del sistema: no necesita permiso de almacenamiento */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        // Contexto de la app, no del fragment: la lectura puede seguir tras rotar
        val appContext = requireContext().applicationContext
        viewModel.uploadAvatar { AvatarImage.fromUri(appContext, uri) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottomsheet_edit_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        avatarImage = view.findViewById(R.id.iv_edit_avatar)
        avatarInitials = view.findViewById(R.id.tv_edit_avatar)
        avatarProgress = view.findViewById(R.id.progress_avatar)
        uploadButton = view.findViewById(R.id.btn_upload_photo)
        removePhoto = view.findViewById(R.id.tv_remove_photo)
        photoUrl = view.findViewById(R.id.et_photo_url)
        useUrl = view.findViewById(R.id.btn_use_url)
        name = view.findViewById(R.id.et_name)
        bio = view.findViewById(R.id.et_bio)
        save = view.findViewById(R.id.btn_save_changes)

        // Solo al abrir: al rotar, los campos conservan lo que el usuario ya escribió
        if (savedInstanceState == null) {
            viewModel.onEditorOpened()
            viewModel.state.value.user?.let {
                name.setText(it.name)
                bio.setText(it.bio.orEmpty())
            }
        }

        view.findViewById<View>(R.id.btn_close).setOnClickListener { dismiss() }

        uploadButton.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // Tocar la foto también abre la galería
        avatarInitials.setOnClickListener { uploadButton.performClick() }
        avatarImage.setOnClickListener { uploadButton.performClick() }

        useUrl.setOnClickListener { viewModel.uploadAvatarFromUrl(photoUrl.text.toString()) }
        removePhoto.setOnClickListener { viewModel.removeAvatar() }
        save.setOnClickListener {
            viewModel.saveProfile(name.text.toString(), bio.text.toString())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::onEvent) }
            }
        }
    }

    private fun render(state: ProfileUiState) {
        // La sesión venció: se cierra el editor y ProfileActivity lleva al login
        if (state.sessionExpired) {
            dismiss()
            return
        }

        val user = state.user
        if (user != null && user != shownUser) {
            if (shownUser != null && user.avatarUrl != shownUser?.avatarUrl) {
                photoUrl.text.clear()
            }
            shownUser = user
            bindAvatar(avatarImage, avatarInitials, user)
        }
        removePhoto.isVisible = user?.avatarUrl != null

        name.error = state.nameError
        bio.error = state.bioError
        photoUrl.error = state.urlError

        val busy = state.avatarBusy || state.saving
        avatarProgress.isVisible = state.avatarBusy
        uploadButton.isEnabled = !busy
        avatarImage.isEnabled = !busy
        avatarInitials.isEnabled = !busy
        removePhoto.isEnabled = !busy
        useUrl.isEnabled = !busy
        save.isEnabled = !busy
    }

    private fun onEvent(event: ProfileEvent) {
        when (event) {
            is ProfileEvent.Message ->
                Toast.makeText(requireContext(), event.text, Toast.LENGTH_SHORT).show()
            ProfileEvent.Saved -> dismiss()
        }
    }

    companion object {
        const val TAG = "EditProfileBottomSheet"
    }
}
