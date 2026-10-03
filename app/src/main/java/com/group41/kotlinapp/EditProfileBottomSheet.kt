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
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.group41.kotlinapp.network.ApiClient
import com.group41.kotlinapp.network.UpdateProfileRequest
import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.network.isUnauthorized
import com.group41.kotlinapp.network.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Editar perfil: foto (galería o URL), nombre y descripción.
 *
 * La foto se sube apenas se elige; nombre y descripción se guardan con
 * "Guardar cambios". Cada cambio se avisa a ProfileActivity con RESULT_KEY.
 */
class EditProfileBottomSheet : BottomSheetDialogFragment() {

    private lateinit var user: UserDto

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
        if (uri != null) uploadPhoto { AvatarImage.fromUri(requireContext(), uri) }
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

        user = userFrom(requireArguments())

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

        name.setText(user.name)
        bio.setText(user.bio.orEmpty())
        showAvatar()

        view.findViewById<View>(R.id.btn_close).setOnClickListener { dismiss() }

        uploadButton.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        // Tocar la foto también abre la galería
        avatarInitials.setOnClickListener { uploadButton.performClick() }
        avatarImage.setOnClickListener { uploadButton.performClick() }

        useUrl.setOnClickListener {
            val url = photoUrl.text.toString().trim()
            if (url.isEmpty()) {
                photoUrl.error = "Pega la URL de una imagen"
                return@setOnClickListener
            }
            uploadPhoto { AvatarImage.fromUrl(url) }
        }

        removePhoto.setOnClickListener { removePhoto() }
        save.setOnClickListener { saveProfile() }
    }

    private fun uploadPhoto(prepare: () -> ByteArray) {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { prepare() }
                onUserUpdated(ApiClient.api.uploadAvatar(AvatarImage.toPart(jpeg)))
                photoUrl.text.clear()
                toast("Foto actualizada")
            } catch (e: AvatarException) {
                toast(e.message.orEmpty())
            } catch (e: Exception) {
                handleApiError(e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun removePhoto() {
        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                onUserUpdated(ApiClient.api.deleteAvatar())
            } catch (e: Exception) {
                handleApiError(e)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun saveProfile() {
        val newName = name.text.toString().trim()
        val newBio = bio.text.toString().trim()

        // Mismas reglas que UpdateProfileRequest en el backend
        if (newName.isEmpty()) {
            name.error = "Escribe tu nombre"
            return
        }
        if (newName.length > 80) {
            name.error = "Máximo 80 caracteres"
            return
        }
        if (newBio.length > 300) {
            bio.error = "Máximo 300 caracteres"
            return
        }

        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                onUserUpdated(ApiClient.api.updateMe(UpdateProfileRequest(newName, newBio)))
                dismiss()
            } catch (e: Exception) {
                handleApiError(e)
                setBusy(false)
            }
        }
    }

    private fun onUserUpdated(updated: UserDto) {
        user = updated
        // Para que el bottom sheet recreado (ej. al rotar) muestre lo último
        arguments = argsFor(updated)
        showAvatar()
        parentFragmentManager.setFragmentResult(RESULT_KEY, argsFor(updated))
    }

    private fun showAvatar() {
        bindAvatar(avatarImage, avatarInitials, user)
        removePhoto.isVisible = user.avatarUrl != null
    }

    private fun handleApiError(e: Exception) {
        if (e.isUnauthorized()) {
            // El authenticator ya intentó refrescar: la sesión venció
            ApiClient.tokens.clear()
            dismiss()
            activity?.openLogin("Tu sesión expiró. Inicia sesión de nuevo")
            return
        }
        toast(e.userMessage())
    }

    private fun setBusy(busy: Boolean) {
        if (view == null) return
        avatarProgress.isVisible = busy
        uploadButton.isEnabled = !busy
        avatarImage.isEnabled = !busy
        avatarInitials.isEnabled = !busy
        removePhoto.isEnabled = !busy
        useUrl.isEnabled = !busy
        save.isEnabled = !busy
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        const val TAG = "EditProfileBottomSheet"

        /** ProfileActivity escucha esta clave para refrescar el encabezado */
        const val RESULT_KEY = "profile_updated"

        fun newInstance(user: UserDto) = EditProfileBottomSheet().apply {
            arguments = argsFor(user)
        }

        fun argsFor(user: UserDto) = bundleOf(
            "id" to user.id,
            "email" to user.email,
            "name" to user.name,
            "bio" to user.bio,
            "avatarUrl" to user.avatarUrl
        )

        fun userFrom(bundle: Bundle) = UserDto(
            id = bundle.getString("id").orEmpty(),
            email = bundle.getString("email").orEmpty(),
            name = bundle.getString("name").orEmpty(),
            bio = bundle.getString("bio"),
            avatarUrl = bundle.getString("avatarUrl")
        )
    }
}
