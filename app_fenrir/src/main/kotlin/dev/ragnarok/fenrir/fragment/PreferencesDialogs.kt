package dev.ragnarok.fenrir.fragment

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import de.maxr1998.modernpreferences.PreferencesExtra
import dev.ragnarok.fenrir.Includes
import dev.ragnarok.fenrir.R
import dev.ragnarok.fenrir.activity.alias.BlackFenrirAlias
import dev.ragnarok.fenrir.activity.alias.BlueFenrirAlias
import dev.ragnarok.fenrir.activity.alias.GreenFenrirAlias
import dev.ragnarok.fenrir.activity.alias.LineageFenrirAlias
import dev.ragnarok.fenrir.activity.alias.RedFenrirAlias
import dev.ragnarok.fenrir.activity.alias.ToggleAlias
import dev.ragnarok.fenrir.activity.alias.VKFenrirAlias
import dev.ragnarok.fenrir.activity.alias.VioletFenrirAlias
import dev.ragnarok.fenrir.activity.alias.WhiteFenrirAlias
import dev.ragnarok.fenrir.activity.alias.YellowFenrirAlias
import dev.ragnarok.fenrir.api.model.LocalServerSettings
import dev.ragnarok.fenrir.api.model.PlayerCoverBackgroundSettings
import dev.ragnarok.fenrir.api.model.SlidrSettings
import dev.ragnarok.fenrir.picasso.PicassoInstance
import dev.ragnarok.fenrir.picasso.transforms.EllipseTransformation
import dev.ragnarok.fenrir.picasso.transforms.RoundTransformation
import dev.ragnarok.fenrir.settings.AvatarStyle
import dev.ragnarok.fenrir.settings.Settings
import dev.ragnarok.fenrir.util.coroutines.CoroutinesUtils.fromIOToMain
import dev.ragnarok.fenrir.util.toast.CustomToast.Companion.createCustomToast

class IconSelectDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = View.inflate(requireActivity(), R.layout.icon_select_alert, null)
        view.findViewById<View>(R.id.default_icon).setOnClickListener {
            ToggleAlias.reset(
                requireActivity()
            )
            dismiss()
        }
        view.findViewById<View>(R.id.blue_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                BlueFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.green_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                GreenFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.violet_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                VioletFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.red_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                RedFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.yellow_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                YellowFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.black_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                BlackFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.vk_official).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                VKFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.white_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                WhiteFenrirAlias::class.java
            )
            dismiss()
        }
        view.findViewById<View>(R.id.lineage_icon).setOnClickListener {
            ToggleAlias.toggleTo(
                requireActivity(),
                LineageFenrirAlias::class.java
            )
            dismiss()
        }
        return MaterialAlertDialogBuilder(requireActivity())
            .setView(view)
            .create()
    }

}

class AvatarStyleDialog : DialogFragment() {
    private fun resolveAvatarStyleViews(style: Int, circle: ImageView, oval: ImageView) {
        when (style) {
            AvatarStyle.CIRCLE -> {
                circle.visibility = View.VISIBLE
                oval.visibility = View.INVISIBLE
            }

            AvatarStyle.OVAL -> {
                circle.visibility = View.INVISIBLE
                oval.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val current = Settings.get()
            .ui()
            .avatarStyle
        val view = View.inflate(requireActivity(), R.layout.dialog_avatar_style, null)
        val ivCircle =
            view.findViewById<ImageView>(R.id.circle_avatar)
        val ivOval =
            view.findViewById<ImageView>(R.id.oval_avatar)
        val ivCircleSelected =
            view.findViewById<ImageView>(R.id.circle_avatar_selected)
        val ivOvalSelected =
            view.findViewById<ImageView>(R.id.oval_avatar_selected)
        ivCircle.setOnClickListener {
            resolveAvatarStyleViews(
                AvatarStyle.CIRCLE,
                ivCircleSelected,
                ivOvalSelected
            )
        }
        ivOval.setOnClickListener {
            resolveAvatarStyleViews(
                AvatarStyle.OVAL,
                ivCircleSelected,
                ivOvalSelected
            )
        }
        resolveAvatarStyleViews(current, ivCircleSelected, ivOvalSelected)
        PicassoInstance.with()
            .load(R.drawable.ava_settings)
            .transform(RoundTransformation())
            .into(ivCircle)
        PicassoInstance.with()
            .load(R.drawable.ava_settings)
            .transform(EllipseTransformation())
            .into(ivOval)
        return MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.avatar_style_title)
            .setView(view)
            .setPositiveButton(R.string.button_ok) { _, _ ->
                val circle = ivCircleSelected.isVisible
                Settings.get()
                    .ui()
                    .storeAvatarStyle(if (circle) AvatarStyle.CIRCLE else AvatarStyle.OVAL)
                PicassoInstance.clear_cache()
                parentFragmentManager.setFragmentResult(
                    PreferencesExtra.RECREATE_ACTIVITY_REQUEST,
                    Bundle()
                )
                dismiss()
            }
            .setNegativeButton(R.string.button_cancel, null)
            .create()
    }

}

class PlayerBackgroundDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view =
            View.inflate(requireActivity(), R.layout.entry_player_background, null)
        val enabledRotation: MaterialSwitch = view.findViewById(R.id.enabled_anim)
        val invertRotation: MaterialSwitch =
            view.findViewById(R.id.edit_invert_rotation)
        val fadeSaturation: MaterialSwitch =
            view.findViewById(R.id.edit_fade_saturation)
        val rotationSpeed = view.findViewById<Slider>(R.id.edit_rotation_speed)
        val zoom = view.findViewById<Slider>(R.id.edit_zoom)
        val blur = view.findViewById<Slider>(R.id.edit_blur)
        val textRotationSpeed: MaterialTextView =
            view.findViewById(R.id.text_rotation_speed)
        val textZoom: MaterialTextView = view.findViewById(R.id.text_zoom)
        val textBlur: MaterialTextView = view.findViewById(R.id.text_blur)
        zoom.addOnChangeListener { _, value, _ ->
            textZoom.text = getString(R.string.rotate_scale, value.toInt())
        }
        rotationSpeed.addOnChangeListener { _, value, _ ->
            textRotationSpeed.text = getString(R.string.rotate_speed, value.toInt())
        }
        blur.addOnChangeListener { _, value, _ ->
            textBlur.text = getString(R.string.player_blur, value.toInt())
        }
        val settings = Settings.get()
            .main().playerCoverBackgroundSettings
        enabledRotation.isChecked = settings.enabled_rotation
        invertRotation.isChecked = settings.invert_rotation
        fadeSaturation.isChecked = settings.fade_saturation
        blur.value = settings.blur.toFloat()
        rotationSpeed.value = (settings.rotation_speed * 10).toInt().toFloat()
        zoom.value = ((settings.zoom - 1) * 10).toInt().toFloat()
        textZoom.text =
            getString(R.string.rotate_scale, ((settings.zoom - 1) * 10).toInt())
        textRotationSpeed.text =
            getString(R.string.rotate_speed, (settings.rotation_speed * 10).toInt())
        textBlur.text = getString(R.string.player_blur, settings.blur)
        return MaterialAlertDialogBuilder(requireActivity())
            .setView(view)
            .setCancelable(true)
            .setNegativeButton(R.string.button_cancel, null)
            .setNeutralButton(R.string.set_default) { _, _ ->
                Settings.get()
                    .main().playerCoverBackgroundSettings =
                    PlayerCoverBackgroundSettings().set_default()
                parentFragmentManager.setFragmentResult(
                    PreferencesExtra.RECREATE_ACTIVITY_REQUEST,
                    Bundle()
                )
                dismiss()
            }
            .setPositiveButton(R.string.button_ok) { _, _ ->
                val st = PlayerCoverBackgroundSettings()
                st.enabled_rotation = enabledRotation.isChecked
                st.invert_rotation = invertRotation.isChecked
                st.fade_saturation = fadeSaturation.isChecked
                st.blur = blur.value.toInt()
                st.rotation_speed = rotationSpeed.value.toInt().toFloat() / 10
                st.zoom = 1 + zoom.value.toInt().toFloat() / 10
                Settings.get()
                    .main().playerCoverBackgroundSettings = st
                parentFragmentManager.setFragmentResult(
                    PreferencesExtra.RECREATE_ACTIVITY_REQUEST,
                    Bundle()
                )
                dismiss()
            }.create()
    }
}

class LocalMediaServerDialog : DialogFragment() {
    @SuppressLint("CheckResult")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = View.inflate(
            requireActivity(),
            dev.ragnarok.fenrir_common.R.layout.entry_local_server,
            null
        )
        val url: TextInputEditText = view.findViewById(dev.ragnarok.fenrir_common.R.id.edit_url)
        val password: TextInputEditText =
            view.findViewById(dev.ragnarok.fenrir_common.R.id.edit_password)
        val enabled: MaterialSwitch =
            view.findViewById(dev.ragnarok.fenrir_common.R.id.enabled_server)
        val settings = Settings.get().main().localServer
        url.setText(settings.url)
        password.setText(settings.password)
        enabled.isChecked = settings.enabled

        view.findViewById<MaterialButton>(dev.ragnarok.fenrir_common.R.id.reboot_pc_win)
            .setOnClickListener {
                Includes.networkInterfaces.localServerApi().rebootPC("win")
                    .fromIOToMain({
                        createCustomToast(
                            requireActivity(),
                            view
                        )?.showToastSuccessBottom(R.string.success)
                    }, { createCustomToast(requireActivity(), view)?.showToastThrowable(it) })
            }

        view.findViewById<MaterialButton>(dev.ragnarok.fenrir_common.R.id.reboot_pc_linux)
            .setOnClickListener {
                Includes.networkInterfaces.localServerApi().rebootPC("linux")
                    .fromIOToMain({
                        createCustomToast(
                            requireActivity(),
                            view
                        )?.showToastSuccessBottom(R.string.success)
                    }, { createCustomToast(requireActivity(), view)?.showToastThrowable(it) })
            }

        return MaterialAlertDialogBuilder(requireActivity())
            .setView(view)
            .setCancelable(true)
            .setNegativeButton(R.string.button_cancel, null)
            .setPositiveButton(R.string.button_ok) { _, _ ->
                val enabledVal = enabled.isChecked
                val urlVal = url.editableText.toString()
                val passVal = password.editableText.toString()
                if (enabledVal && (urlVal.isEmpty() || passVal.isEmpty())) {
                    return@setPositiveButton
                }
                val srv = LocalServerSettings()
                srv.enabled = enabledVal
                srv.password = passVal
                srv.url = urlVal
                Settings.get().main().localServer = srv
                Includes.proxySettings.broadcastUpdate(null)
            }
            .create()
    }

}

class SlidrEditDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = View.inflate(requireActivity(), R.layout.entry_slidr_settings, null)

        val verticalSensitive =
            view.findViewById<Slider>(R.id.edit_vertical_sensitive)
        val horizontalSensitive =
            view.findViewById<Slider>(R.id.edit_horizontal_sensitive)
        val textHorizontalSensitive: MaterialTextView =
            view.findViewById(R.id.text_horizontal_sensitive)
        val textVerticalSensitive: MaterialTextView =
            view.findViewById(R.id.text_vertical_sensitive)

        val verticalVelocityThreshold =
            view.findViewById<Slider>(R.id.edit_vertical_velocity_threshold)
        val horizontalVelocityThreshold =
            view.findViewById<Slider>(R.id.edit_horizontal_velocity_threshold)
        val textHorizontalVelocityThreshold: MaterialTextView =
            view.findViewById(R.id.text_horizontal_velocity_threshold)
        val textVerticalVelocityThreshold: MaterialTextView =
            view.findViewById(R.id.text_vertical_velocity_threshold)

        val verticalDistanceThreshold =
            view.findViewById<Slider>(R.id.edit_vertical_distance_threshold)
        val horizontalDistanceThreshold =
            view.findViewById<Slider>(R.id.edit_horizontal_distance_threshold)
        val textHorizontalDistanceThreshold: MaterialTextView =
            view.findViewById(R.id.text_horizontal_distance_threshold)
        val textVerticalDistanceThreshold: MaterialTextView =
            view.findViewById(R.id.text_vertical_distance_threshold)

        verticalSensitive.addOnChangeListener { _, value, fromUser ->
            if (fromUser && value < 20) {
                verticalSensitive.value = 20.0f
                textVerticalSensitive.text = getString(R.string.slidr_sensitive, 20)
            } else {
                textVerticalSensitive.text = getString(R.string.slidr_sensitive, value.toInt())
            }
        }

        horizontalSensitive.addOnChangeListener { _, value, fromUser ->
            if (fromUser && value < 20) {
                horizontalSensitive.value = 20.0f
                textHorizontalSensitive.text = getString(R.string.slidr_sensitive, 20)
            } else {
                textHorizontalSensitive.text =
                    getString(R.string.slidr_sensitive, value.toInt())
            }
        }

        verticalVelocityThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser && value < 4) {
                verticalVelocityThreshold.value = 4.0f
                textVerticalVelocityThreshold.text =
                    getString(R.string.slidr_velocity_threshold, 4)
            } else {
                textVerticalVelocityThreshold.text =
                    getString(R.string.slidr_velocity_threshold, value.toInt())
            }
        }

        horizontalVelocityThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser && value < 4) {
                horizontalVelocityThreshold.value = 4.0f
                textHorizontalVelocityThreshold.text =
                    getString(R.string.slidr_velocity_threshold, 4)
            } else {
                textHorizontalVelocityThreshold.text =
                    getString(R.string.slidr_velocity_threshold, value.toInt())
            }
        }

        verticalDistanceThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser && value < 4) {
                verticalDistanceThreshold.value = 4.0f
                textVerticalDistanceThreshold.text =
                    getString(R.string.slidr_distance_threshold, 4)
            } else {
                textVerticalDistanceThreshold.text =
                    getString(R.string.slidr_distance_threshold, value.toInt())
            }
        }

        horizontalDistanceThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser && value < 4) {
                horizontalDistanceThreshold.value = 4.0f
                textHorizontalDistanceThreshold.text =
                    getString(R.string.slidr_distance_threshold, 4)
            } else {
                textHorizontalDistanceThreshold.text =
                    getString(R.string.slidr_distance_threshold, value.toInt())
            }
        }

        val settings = Settings.get()
            .main().slidrSettings
        verticalSensitive.value = (settings.vertical_sensitive * 100).toInt().toFloat()
        horizontalSensitive.value = (settings.horizontal_sensitive * 100).toInt().toFloat()

        textHorizontalSensitive.text = getString(
            R.string.slidr_sensitive,
            (settings.horizontal_sensitive * 100).toInt()
        )
        textVerticalSensitive.text =
            getString(
                R.string.slidr_sensitive,
                (settings.vertical_sensitive * 100).toInt()
            )

        verticalVelocityThreshold.value =
            (settings.vertical_velocity_threshold * 10).toInt().toFloat()
        horizontalVelocityThreshold.value =
            (settings.horizontal_velocity_threshold * 10).toInt().toFloat()

        textHorizontalVelocityThreshold.text = getString(
            R.string.slidr_velocity_threshold,
            (settings.horizontal_velocity_threshold * 10).toInt()
        )
        textVerticalVelocityThreshold.text = getString(
            R.string.slidr_velocity_threshold,
            (settings.vertical_velocity_threshold * 10).toInt()
        )

        verticalDistanceThreshold.value =
            (settings.vertical_distance_threshold * 100).toInt().toFloat()
        horizontalDistanceThreshold.value =
            (settings.horizontal_distance_threshold * 100).toInt().toFloat()

        textHorizontalDistanceThreshold.text = getString(
            R.string.slidr_distance_threshold,
            (settings.horizontal_distance_threshold * 100).toInt()
        )
        textVerticalDistanceThreshold.text = getString(
            R.string.slidr_distance_threshold,
            (settings.vertical_distance_threshold * 100).toInt()
        )

        return MaterialAlertDialogBuilder(requireActivity())
            .setView(view)
            .setCancelable(true)
            .setNegativeButton(R.string.button_cancel, null)
            .setNeutralButton(R.string.set_default) { _, _ ->
                Settings.get()
                    .main().slidrSettings = SlidrSettings().set_default()
                parentFragmentManager.setFragmentResult(
                    PreferencesExtra.RECREATE_ACTIVITY_REQUEST,
                    Bundle()
                )
                dismiss()
            }
            .setPositiveButton(R.string.button_ok) { _, _ ->
                val st = SlidrSettings()
                st.horizontal_sensitive = horizontalSensitive.value.toInt().toFloat() / 100
                st.vertical_sensitive = verticalSensitive.value.toInt().toFloat() / 100

                st.horizontal_velocity_threshold =
                    horizontalVelocityThreshold.value.toInt().toFloat() / 10
                st.vertical_velocity_threshold =
                    verticalVelocityThreshold.value.toInt().toFloat() / 10

                st.horizontal_distance_threshold =
                    horizontalDistanceThreshold.value.toInt().toFloat() / 100
                st.vertical_distance_threshold =
                    verticalDistanceThreshold.value.toInt().toFloat() / 100
                Settings.get()
                    .main().slidrSettings = st
                parentFragmentManager.setFragmentResult(
                    PreferencesExtra.RECREATE_ACTIVITY_REQUEST,
                    Bundle()
                )
                dismiss()
            }.create()
    }
}
