package com.nkd.screenrecorder.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.transition.TransitionManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.nkd.screenrecorder.R
import com.nkd.screenrecorder.activities.MainActivity
import com.nkd.screenrecorder.adapter.EditingToolsAdapter
import com.nkd.screenrecorder.adapter.EditingToolsAdapter.ToolModel
import com.nkd.screenrecorder.adapter.FilterViewAdapter
import com.nkd.screenrecorder.databinding.FragmentEditImageBinding
import com.nkd.screenrecorder.utils.ToolType
import com.nkd.screenrecorder.widgets.EmojiBSFragment
import com.nkd.screenrecorder.widgets.FileSaveHelper
import com.nkd.screenrecorder.widgets.PropertiesBSFragment
import com.nkd.screenrecorder.widgets.ShapeBSFragment
import com.nkd.screenrecorder.widgets.StickerBSFragment
import com.nkd.screenrecorder.widgets.TextEditorDialogFragment
import ja.burhanrashid52.photoeditor.OnPhotoEditorListener
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.SaveFileResult
import ja.burhanrashid52.photoeditor.SaveSettings
import ja.burhanrashid52.photoeditor.TextStyleBuilder
import ja.burhanrashid52.photoeditor.ViewType
import ja.burhanrashid52.photoeditor.shape.ShapeBuilder
import ja.burhanrashid52.photoeditor.shape.ShapeType
import kotlinx.coroutines.launch
import java.io.File

class EditImageFragment : Fragment(), OnPhotoEditorListener,
    PropertiesBSFragment.Properties, ShapeBSFragment.Properties, EmojiBSFragment.EmojiListener,
    StickerBSFragment.StickerListener {

    private val TAG: String = EditImageFragment::class.java.simpleName
    private lateinit var binding: FragmentEditImageBinding
    private val args: EditImageFragmentArgs by navArgs()
    private lateinit var mPhotoEditor: PhotoEditor
    private lateinit var mEditingToolsAdapter: EditingToolsAdapter
    private lateinit var mFilterAdapter: FilterViewAdapter
    private val mToolList: MutableList<ToolModel> = ArrayList()
    private lateinit var mPropertiesBSFragment: PropertiesBSFragment
    private lateinit var mShapeBSFragment: ShapeBSFragment
    private lateinit var mShapeBuilder: ShapeBuilder
    private lateinit var mEmojiBSFragment: EmojiBSFragment
    private lateinit var mStickerBSFragment: StickerBSFragment
    private lateinit var mWonderFont: Typeface
    private lateinit var mSaveFileHelper: FileSaveHelper
    private val mConstraintSet = ConstraintSet()
    private var mIsFilterVisible = false
    private var mProgressDialog: ProgressDialog? = null

    @VisibleForTesting
    var mSaveImageUri: Uri? = null
    private var mPermission: String? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            saveImage()
        }

    fun requestPermission(permission: String): Boolean {
        val isGranted =
            ContextCompat.checkSelfPermission(
                requireActivity(),
                permission
            ) == PackageManager.PERMISSION_GRANTED
        if (!isGranted) {
            mPermission = permission
            permissionLauncher.launch(permission)
        }
        return isGranted
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        binding = FragmentEditImageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mWonderFont = Typeface.createFromAsset(requireActivity().assets, "beyond_wonderland.ttf")
        mSaveFileHelper = FileSaveHelper(requireActivity() as AppCompatActivity)
        mPropertiesBSFragment = PropertiesBSFragment()
        mEmojiBSFragment = EmojiBSFragment()
        mStickerBSFragment = StickerBSFragment()
        mShapeBSFragment = ShapeBSFragment()
        mStickerBSFragment.setStickerListener(this)
        mEmojiBSFragment.setEmojiListener(this)
        mPropertiesBSFragment.setPropertiesChangeListener(this)
        mShapeBSFragment.setPropertiesChangeListener(this)
        binding.photoEditorView.parent.requestDisallowInterceptTouchEvent(true)
        binding.photoEditorView.setOnTouchListener { _, event ->
            Log.e("TOUCH", "x=${event.x} y=${event.y}")
            false
        }
        binding.apply {
            /*  photoEditorView.setOnTouchListener { v, event ->
                  when (event.action) {
                      MotionEvent.ACTION_DOWN,
                      MotionEvent.ACTION_MOVE -> {
                          v.parent?.requestDisallowInterceptTouchEvent(true)
                      }

                      MotionEvent.ACTION_UP -> {
                          v.parent?.requestDisallowInterceptTouchEvent(false)
                      }
                  }
                  false
              }*/
            val uri = args.imageUrl.toUri()

            val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(requireActivity().contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
            }

            val safeBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)

            binding.photoEditorView.source.setImageBitmap(safeBitmap)

            photoEditorView.source.scaleType = ImageView.ScaleType.FIT_XY
            photoEditorView.source.adjustViewBounds = true
            mPhotoEditor = PhotoEditor.Builder(requireContext(), photoEditorView)
                .setPinchTextScalable(false)
                .build()
            mPhotoEditor.setOnPhotoEditorListener(this@EditImageFragment)

            mToolList.add(ToolModel(getString(R.string.tool_shape), R.drawable.ic_oval, ToolType.SHAPE))
            mToolList.add(ToolModel(getString(R.string.tool_text), R.drawable.ic_text, ToolType.TEXT))
            mToolList.add(ToolModel(getString(R.string.tool_eraser), R.drawable.ic_eraser, ToolType.ERASER))
            mToolList.add(ToolModel(getString(R.string.tool_filter), R.drawable.ic_photo_filter, ToolType.FILTER))
            mToolList.add(ToolModel(getString(R.string.tool_emoji), R.drawable.ic_insert_emoticon, ToolType.EMOJI))
            mToolList.add(ToolModel(getString(R.string.tool_sticker), R.drawable.ic_sticker, ToolType.STICKER))

            mEditingToolsAdapter = EditingToolsAdapter(mToolList, { toolType ->
                when (toolType) {
                    ToolType.SHAPE -> {
                        mPhotoEditor.setBrushDrawingMode(true)
                        mShapeBuilder = ShapeBuilder().withShapeType(ShapeType.Brush)
                            .withShapeSize(2f)   // 👈 increase
                            .withShapeOpacity(255)

                        mPhotoEditor.setShape(mShapeBuilder)

                        binding.txtCurrentTool.setText(R.string.label_shape)
                        showBottomSheetDialogFragment(mShapeBSFragment)
                    }

                    ToolType.TEXT -> {
                        val textEditorDialogFragment =
                            TextEditorDialogFragment.show(requireActivity() as AppCompatActivity)
                        textEditorDialogFragment.setOnTextEditorListener(object :
                            TextEditorDialogFragment.TextEditorListener {
                            override fun onDone(inputText: String, colorCode: Int) {
                                val styleBuilder = TextStyleBuilder()
                                styleBuilder.withTextColor(colorCode)
                                mPhotoEditor.addText(inputText, styleBuilder)
                                binding.txtCurrentTool.setText(R.string.label_text)
                            }
                        })
                    }

                    ToolType.ERASER -> {
                        mPhotoEditor.brushEraser()
                        binding.txtCurrentTool.setText(R.string.label_eraser_mode)
                    }

                    ToolType.FILTER -> {
                        binding.txtCurrentTool.setText(R.string.label_filter)
                        showFilter(true)
                    }

                    ToolType.EMOJI -> showBottomSheetDialogFragment(mEmojiBSFragment)
                    ToolType.STICKER -> showBottomSheetDialogFragment(mStickerBSFragment)
                }
            })
            rvConstraintTools.adapter = mEditingToolsAdapter

            mFilterAdapter = FilterViewAdapter(
                onFilterSelected = { photoFilter ->
                    mPhotoEditor.setFilterEffect(photoFilter)
                })
            rvFilterView.adapter = mFilterAdapter

            imgUndo.setOnClickListener {
                imgUndo.isEnabled = mPhotoEditor.undo()
                imgRedo.isEnabled = mPhotoEditor.isRedoAvailable
            }
            imgRedo.setOnClickListener {
                imgUndo.isEnabled = mPhotoEditor.isUndoAvailable
                imgRedo.isEnabled = mPhotoEditor.redo()
            }
            imgSave.setOnClickListener {
                saveImage()
            }
            imgShare.setOnClickListener {
                shareImage()
            }
            imgClose.setOnClickListener {
                findNavController().navigateUp()
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isEnabled) {
                    if (mIsFilterVisible) {
                        showFilter(false)
                        binding.txtCurrentTool.setText(R.string.app_name)
                    } else if (!mPhotoEditor.isCacheEmpty) {
                        showSaveDialog()
                    } else {
                        isEnabled = false
                        findNavController().navigateUp()
                    }
                }
            }
        })
    }

    fun showLoading(message: String) {
        mProgressDialog = ProgressDialog(requireContext())
        mProgressDialog?.run {
            setMessage(message)
            setProgressStyle(ProgressDialog.STYLE_SPINNER)
            setCancelable(false)
            show()
        }
    }

    fun hideLoading() {
        mProgressDialog?.dismiss()
    }

    fun showSnackbar(message: String) {
        Toast.makeText(requireActivity(), message, Toast.LENGTH_SHORT).show()
    }

    private fun saveImage() {
        val fileName = System.currentTimeMillis().toString() + ".png"
        val hasStoragePermission = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (hasStoragePermission || FileSaveHelper.isSdkHigherThan28()) {
            showLoading(getString(R.string.msg_saving))
            mSaveFileHelper.createFile(fileName, object : FileSaveHelper.OnFileCreateResult {

                @SuppressLint("MissingPermission")
                override fun onFileCreateResult(
                    created: Boolean,
                    filePath: String?,
                    error: String?,
                    uri: Uri?
                ) {
                    Log.e(TAG, "OnFileCreateResult called ${created} ${filePath}")
                    lifecycleScope.launch {

                        if (created && filePath != null) {

                            val saveSettings = SaveSettings.Builder()
                                .setClearViewsEnabled(true)
                                .setTransparencyEnabled(true)
                                .build()

                            val result = mPhotoEditor.saveAsFile(filePath, saveSettings)

                            if (result is SaveFileResult.Success) {
                                // 1. Clear IS_PENDING synchronously → system renames .pending-... to real name
                                if (FileSaveHelper.isSdkHigherThan28()) {
                                    val values = ContentValues().apply {
                                        put(MediaStore.Images.Media.IS_PENDING, 0)
                                    }
                                    requireActivity().contentResolver.update(
                                        uri!!,
                                        values,
                                        null,
                                        null
                                    )
                                }

                                // 2. Query the actual renamed path from the URI
                                val realFilePath = requireActivity().contentResolver.query(
                                    uri!!,
                                    arrayOf(MediaStore.Images.Media.DATA),
                                    null, null, null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst())
                                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                                    else filePath  // fallback
                                } ?: filePath

                                // 3. Now move the clean-named file
                                val realPath = moveOutputToInputFolder(
                                    requireActivity(),
                                    args.imageUrl,
                                    realFilePath!!
                                )
                                Log.e(TAG, "Moved to: $realPath")

                                hideLoading()
                                showSnackbar(getString(R.string.msg_image_saved))
                                mSaveImageUri = uri
                                binding.photoEditorView.source.setImageURI(mSaveImageUri)
                            } else {
                                hideLoading()
                                showSnackbar(getString(R.string.msg_image_save_failed))
                            }
                        } else {
                            hideLoading()
                            error?.let { showSnackbar(error) }
                        }
                    }
                }
            })
        } else {
            requestPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun shareImage() {
        val saveImageUri = mSaveImageUri
        if (saveImageUri == null) {
            showSnackbar(getString(R.string.msg_save_image_to_share))
            return
        }

        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "image/*"
        intent.putExtra(Intent.EXTRA_STREAM, buildFileProviderUri(saveImageUri))
        startActivity(Intent.createChooser(intent, getString(R.string.msg_share_image)))
    }

    private fun buildFileProviderUri(uri: Uri): Uri {
        if (FileSaveHelper.isSdkHigherThan28()) {
            return uri
        }
        val path: String = uri.path ?: throw IllegalArgumentException("URI Path Expected")

        return FileProvider.getUriForFile(
            requireActivity(),
            requireActivity().packageName,
            File(path)
        )
    }

    fun moveOutputToInputFolder(
        context: Context,
        inputPath: String,
        outputPath: String
    ): String {
        val outputFile = File(outputPath)

        // --- Plain file path ---
        if (!inputPath.startsWith("content://")) {
            val inputFile = File(inputPath)
            val dest = File(inputFile.parentFile, outputFile.name)
            outputFile.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }

        val resolver = context.contentResolver
        val inputUri = inputPath.toUri()

        // --- SAF tree URI (e.g. externalstorage.documents/tree/...) ---
        if (DocumentsContract.isTreeUri(inputUri)) {
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                inputUri.authority!!,
                DocumentsContract.getTreeDocumentId(inputUri)   // safe here
            )
            val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return outputPath
            val newFile = tree.createFile("video/mp4", outputFile.nameWithoutExtension)
                ?: return outputPath
            resolver.openOutputStream(newFile.uri)?.use { out ->
                outputFile.inputStream().use { it.copyTo(out) }
            }
            return newFile.uri.toString()
        }

        // --- MediaStore URI (content://media/...) or other single-document URI ---
        val realPath = resolveRealPath(context, inputUri)
        if (realPath != null) {
            val dest = File(File(realPath).parentFile, outputFile.name)
            outputFile.copyTo(dest, overwrite = true)
            return dest.absolutePath
        }

        // Last resort: stream directly over the original URI
        resolver.openOutputStream(inputUri)?.use { out ->
            outputFile.inputStream().use { it.copyTo(out) }
        }
        return inputPath
    }

    /**
     * Resolves a MediaStore (or similar) content URI to an absolute file path.
     * Returns null if the path cannot be determined.
     */
    private fun resolveRealPath(context: Context, uri: Uri): String? {
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    cursor.getString(col).takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun showSaveDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setMessage(getString(R.string.msg_save_image))
        builder.setPositiveButton(getString(R.string.btn_save)) { _: DialogInterface?, _: Int -> saveImage() }
        builder.setNegativeButton(getString(R.string.btn_cancel)) { dialog: DialogInterface, _: Int -> dialog.dismiss() }
        builder.setNeutralButton(getString(R.string.btn_discard)) { _: DialogInterface?, _: Int -> findNavController().navigateUp() }
        builder.create().show()
    }

    private fun showBottomSheetDialogFragment(fragment: BottomSheetDialogFragment?) {
        if (fragment == null || fragment.isAdded) {
            return
        }
        fragment.show(childFragmentManager, fragment.tag)
    }

    private fun showFilter(isVisible: Boolean) {
        mIsFilterVisible = isVisible
        mConstraintSet.clone(binding.rootView)

        val rvFilterId: Int = binding.rvFilterView.id

        if (isVisible) {
            mConstraintSet.clear(rvFilterId, ConstraintSet.START)
            mConstraintSet.connect(
                rvFilterId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START
            )
            mConstraintSet.connect(
                rvFilterId, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END
            )
        } else {
            mConstraintSet.connect(
                rvFilterId, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.END
            )
            mConstraintSet.clear(rvFilterId, ConstraintSet.END)
        }

        val changeBounds = androidx.transition.ChangeBounds()
        changeBounds.duration = 350
        changeBounds.interpolator = AnticipateOvershootInterpolator(1.0f)
        TransitionManager.beginDelayedTransition(binding.rootView, changeBounds)

        mConstraintSet.applyTo(binding.rootView)
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            (requireActivity() as MainActivity).hideTopCardView(false)
            (requireActivity() as MainActivity).hideBottomNavAndFab(false)
            hideSystemUI()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRemoving) {
            (requireActivity() as MainActivity).hideTopCardView(true)
            (requireActivity() as MainActivity).hideBottomNavAndFab(true)
            showSystemBars()
        }
    }

    private fun hideSystemUI() {
        // Access activity's window through requireActivity()
        val window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern API (30+)
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy API (below 30)
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun showSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(
            requireActivity().window, requireActivity().window.decorView
        )
        // Show both the status bar and the navigation bar
        windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onEditTextChangeListener(
        rootView: View, text: String, colorCode: Int
    ) {
        val textEditorDialogFragment = TextEditorDialogFragment.show(
            requireActivity() as AppCompatActivity, text.toString(), colorCode
        )
        textEditorDialogFragment.setOnTextEditorListener(object :
            TextEditorDialogFragment.TextEditorListener {
            override fun onDone(inputText: String, colorCode: Int) {
                val styleBuilder = TextStyleBuilder()
                styleBuilder.withTextColor(colorCode)
                mPhotoEditor.editText(rootView, inputText, styleBuilder)
                binding.txtCurrentTool.setText(R.string.label_text)
            }
        })
    }

    override fun onAddViewListener(
        viewType: ViewType, numberOfAddedViews: Int
    ) {
        Log.d(
            TAG,
            "onAddViewListener() called with: viewType = [$viewType], numberOfAddedViews = [$numberOfAddedViews]"
        )

        binding.imgUndo.isEnabled = mPhotoEditor.isUndoAvailable
        binding.imgRedo.isEnabled = mPhotoEditor.isRedoAvailable
    }

    override fun onRemoveViewListener(
        viewType: ViewType, numberOfAddedViews: Int
    ) {
        Log.d(
            TAG,
            "onRemoveViewListener() called with: viewType = [$viewType], numberOfAddedViews = [$numberOfAddedViews]"
        )

        binding.imgUndo.isEnabled = mPhotoEditor.isUndoAvailable
        binding.imgRedo.isEnabled = mPhotoEditor.isRedoAvailable
    }

    override fun onStartViewChangeListener(viewType: ViewType) {
        Log.d(TAG, "onStartViewChangeListener() called with: viewType = [$viewType]")
    }

    override fun onStopViewChangeListener(viewType: ViewType) {
        Log.d(TAG, "onStopViewChangeListener() called with: viewType = [$viewType]")
    }

    override fun onTouchSourceImage(event: MotionEvent) {
        Log.d(TAG, "onTouchView() called with: event = [$event]")
    }

    override fun onColorChanged(colorCode: Int) {
        mPhotoEditor.setShape(mShapeBuilder.withShapeColor(colorCode))
        binding.txtCurrentTool.setText(R.string.label_brush)
    }

    override fun onOpacityChanged(opacity: Int) {
        mPhotoEditor.setShape(mShapeBuilder.withShapeOpacity(opacity))
        binding.txtCurrentTool.setText(R.string.label_brush)
    }

    override fun onShapeSizeChanged(shapeSize: Int) {
        mPhotoEditor.setShape(mShapeBuilder.withShapeSize(shapeSize.toFloat()))
        binding.txtCurrentTool.setText(R.string.label_brush)
    }

    override fun onShapePicked(shapeType: ShapeType) {
        mPhotoEditor.setShape(mShapeBuilder.withShapeType(shapeType))
    }

    override fun onEmojiClick(emojiUnicode: String) {
        mPhotoEditor.addEmoji(emojiUnicode)
        binding.txtCurrentTool.setText(R.string.label_emoji)
    }

    override fun onStickerClick(bitmap: Bitmap) {
        mPhotoEditor.addImage(bitmap)
        binding.txtCurrentTool.setText(R.string.label_sticker)
    }

}