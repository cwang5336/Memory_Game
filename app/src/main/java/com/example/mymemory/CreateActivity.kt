package com.example.mymemory

import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import com.example.mymemory.ImagePickerAdapter
import com.example.mymemory.R



import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.mymemory.models.BoardSize
import com.example.mymemory.utils.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import java.io.ByteArrayOutputStream

class CreateActivity : AppCompatActivity() {

    companion object{
        private const val TAG = "CreateActivity"
        private const val PICK_PHOTO_CODE = 6969
        private const val READ_EXTERNAL_PHOTO_CODE = 420
        private const val READ_PHOTOS_PERMISSION = android.Manifest.permission.READ_EXTERNAL_STORAGE
        private const val MIN_GAME_NAME_LENGTH = 3
        private const val MAX_GAME_NAME_LENGTH = 14
    }

    private lateinit var rvImagePicker: RecyclerView
    private lateinit var etGameName: EditText
    private lateinit var btnSave: Button
    private lateinit var pbUploading: ProgressBar

    private lateinit var adapter: ImagePickerAdapter
    private lateinit var boardSize: BoardSize
    private var numImagesRequired = -1
    private val chosenImageUris = mutableListOf<Uri>()
    private val storage = Firebase.storage
    private val db = Firebase.firestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create)

        rvImagePicker = findViewById(R.id.rvImagePicker)
        etGameName = findViewById(R.id.etGameName)
        btnSave = findViewById(R.id.btnSave)
        pbUploading = findViewById(R.id.pdUploading)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        boardSize = intent.getSerializableExtra(EXTRA_BOARD_SIZE) as BoardSize
        numImagesRequired = boardSize.getNumPairs()
        supportActionBar?.title = "Choose pics (0 / $numImagesRequired)"

        btnSave.setOnClickListener{
            saveDataToFirebase()
        }
        //sets the max length of name to 14
        etGameName.filters = arrayOf(InputFilter.LengthFilter(MAX_GAME_NAME_LENGTH))
        etGameName.addTextChangedListener(object: TextWatcher{
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                btnSave.isEnabled = shouldEnableSaveButton()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })

        adapter = ImagePickerAdapter(this, chosenImageUris, boardSize, object: ImagePickerAdapter.ImageClickerListener{
            override fun onPlaceholderClicked() {
                //allows for the user to pick photos from their phone
                if(isPermissionGranted(this@CreateActivity, READ_PHOTOS_PERMISSION)){
                    launchIntentForPhotos()
                }else{
                    //this asks the user for permission to allow access to their external files
                    requestPermission(this@CreateActivity, READ_PHOTOS_PERMISSION, READ_EXTERNAL_PHOTO_CODE)
                }
            }
        })

        rvImagePicker.adapter = adapter
        rvImagePicker.setHasFixedSize(true)
        rvImagePicker.layoutManager = GridLayoutManager(this,boardSize.getWidth())

    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if(requestCode == READ_EXTERNAL_PHOTO_CODE){
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                launchIntentForPhotos()
            }else{
                Toast.makeText(this, "In order to create a custom game, you need to provide access to your photos", Toast.LENGTH_LONG).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home){
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode != PICK_PHOTO_CODE || resultCode != Activity.RESULT_OK || data == null){
            Log.w(TAG, "Did not get data back from launched activity, user likely cancled flow")
            return
        }
        val selectedUri = data.data
        val clipData = data.clipData
        if(clipData != null){
            Log.i(TAG, "clipData numImage ${clipData.itemCount}: $clipData")
            for (i in 0 until clipData.itemCount){
                val clipData = clipData.getItemAt(i)
                if (chosenImageUris.size < numImagesRequired){
                    chosenImageUris.add(clipData.uri)
                }
            }
        }else if (selectedUri != null){
            Log.i(TAG, "data: $selectedUri")
            chosenImageUris.add(selectedUri)
        }
        adapter.notifyDataSetChanged()
        supportActionBar?.title = "Choose pics (${chosenImageUris.size} / $numImagesRequired)"
        btnSave.isEnabled = shouldEnableSaveButton()
    }

    private fun saveDataToFirebase() {
        Log.i(TAG, "Going to save data to Firebase")
        val customGameName = etGameName.text.toString().trim()
        //prevents user from spamming the button so button gets disabled once gane is trying to save
        btnSave.isEnabled = false
        db.collection("games").document(customGameName).get().addOnSuccessListener { document ->
            if (document != null && document.data != null) {
                AlertDialog.Builder(this)
                        .setTitle("Name taken")
                        .setMessage("A game already exists with the name $customGameName'. Please choose another")
                        .setPositiveButton("OK", null)
                        .show()
                //reenables button for user to save
                btnSave.isEnabled = true
            } else {
                handleImageUploading(customGameName)
            }
        }.addOnFailureListener {exception ->
            Log.e(TAG, "Encountered error while saving memory game", exception)
            Toast.makeText(this, "Encountered error while saving memory game", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImageUploading(customGameName: String) {
        //shows a progress bar to show status of upload to firestone
        pbUploading.visibility = View.VISIBLE
        val uploadedImageUrls = mutableListOf<String>()
        var didEncounterError = false
        for ((index, photoUri) in chosenImageUris.withIndex()) {
            val imageByteArray = getImageByteArray(photoUri)
            val filePath = "images/$customGameName/${System.currentTimeMillis()}-${index}.jpg"
            val photoReference = storage.reference.child(filePath)
            photoReference.putBytes(imageByteArray)
                    .continueWithTask { photoUploadTask ->
                        Log.i(TAG, "uploaded bytes: ${photoUploadTask.result?.bytesTransferred}")
                        photoReference.downloadUrl
                    }.addOnCompleteListener { downloadUrlTask ->
                        if (!downloadUrlTask.isSuccessful) {
                            Log.e(TAG, "Exception with Firebase storage", downloadUrlTask.exception)
                            Toast.makeText(this, "Failed to upload image", Toast.LENGTH_SHORT).show()
                            didEncounterError = true
                            return@addOnCompleteListener
                        }
                        if (didEncounterError) {
                            //when uploading progress bar is gone
                            pbUploading.visibility = View.GONE
                            return@addOnCompleteListener
                        }
                        pbUploading.progress = uploadedImageUrls.size * 100 / chosenImageUris.size
                        val downloadUrl = downloadUrlTask.result.toString()
                        uploadedImageUrls.add(downloadUrl)
                        Log.i(TAG, "Finished uploading $photoUri, Num uploaded: ${uploadedImageUrls.size}")
                        if (uploadedImageUrls.size == chosenImageUris.size) {
                            handleAllImagesUploaded(customGameName, uploadedImageUrls)
                        }
                    }
        }
    }

    private fun handleAllImagesUploaded(gameName: String, imageUrls: MutableList<String>) {
        //uploads to firestone
        //stores the upload into an document which will be in a collection call games
        db.collection("games").document(gameName)
                //associate data to images
                .set(mapOf("images" to imageUrls))
                //verifies if the action is completed
                .addOnCompleteListener { gameCreationTask ->
                    pbUploading.visibility = View.GONE
                    if (!gameCreationTask.isSuccessful) {
                        Log.e(TAG, "Exception with game creation", gameCreationTask.exception)
                        Toast.makeText(this, "Failed game creation", Toast.LENGTH_SHORT).show()
                        return@addOnCompleteListener
                    }
                    //tells user custom game creation is sucessfull
                    Log.i(TAG, "Successfully created game $gameName")
                    AlertDialog.Builder(this)
                            .setTitle("Upload complete! Let's play your game '$gameName'")
                            //when user tap on okk button it will bring them to their created game
                            .setPositiveButton("OK") { _, _ ->
                                val resultData = Intent()
                                resultData.putExtra(EXTRA_GAME_NAME, gameName)
                                setResult(Activity.RESULT_OK, resultData)
                                finish()
                            }.show()
                }
    }

    private fun getImageByteArray(photoUri: Uri): ByteArray {
        val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, photoUri)
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(contentResolver, photoUri)
        }
        Log.i(TAG, "Original width ${originalBitmap.width} and height ${originalBitmap.height}")
        val scaledBitmap = BitmapScaler.scaleToFitHeight(originalBitmap, 250)
        Log.i(TAG, "Scaled width ${scaledBitmap.width} and height ${scaledBitmap.height}")
        val byteOutputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteOutputStream) //decreases quality to 60%
        return byteOutputStream.toByteArray()
    }

    //decided is the save button in custom game should be enabled or not
    private fun shouldEnableSaveButton(): Boolean {
        if (chosenImageUris.size != numImagesRequired){
            return false
        }
        if (etGameName.text.isBlank() || etGameName.text.length < MIN_GAME_NAME_LENGTH){
            return false
        }
        return true
    }

    private fun launchIntentForPhotos() {
        val intent = Intent(Intent.ACTION_PICK)
        //tells that image is the only thing we care about
        intent.type = "image/*"
        //allows for mutiple pic selection at the same time
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(Intent.createChooser(intent,"Choose pics"), PICK_PHOTO_CODE)
    }

}
