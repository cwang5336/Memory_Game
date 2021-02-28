package com.example.mymemory.models

enum class BoardSize(val numCards: Int){

    //different options for the number of cards depending on mode of game
    EASY(8),
    MEDIUM(18),
    HARD(24);

    companion object{
        fun getByValue(value: Int ) = values().first{ it.numCards == value }
    }

    //based on the mode of the game this will determine the number of
    //cullom which will be used as a measurement for width
    fun getWidth(): Int{
        return when (this){
            EASY -> 2
            MEDIUM -> 3
            HARD -> 4
        }
    }

    fun getHeight(): Int {
        return numCards / getWidth()
    }
    //This will determine how many pairs there are
    fun getNumPairs(): Int {
        return numCards / 2
    }
}