package com.fergusonconsultancy.sete

import java.util.Calendar

/**
 * Each kind of plant has a character drawn from how it really behaves: the Peace Lily faints
 * theatrically when dry and recovers within the hour, orchids want a proper soak and a proper
 * drain, succulents would rather be ignored. They speak in reminders, in the plant's details,
 * and when watered. Warm and a little cheeky, never nagging.
 */
data class Character(
    val title: String,
    val thirsty: List<String>,
    val thanks: List<String>,
    val damp: List<String>,
)

object Personality {
    private val byKind = mapOf(
        "strelitzia" to Character("The diva from the tropics",
            listOf("Dreaming of Durban, and very dry about it.", "Leaf edges curling. That's a hint.", "Sat by the radiator all day. Parched."),
            listOf("Ahh. Practically tropical.", "Now that's more like it.", "Fanning out in gratitude."),
            listOf("Still damp? Fair. I'll sunbathe instead.", "Noted. I'll keep it classy.")),
        "citrus" to Character("The Mediterranean",
            listOf("Dreaming of Seville. Also of water.", "The oranges are getting nervous.", "A bit dry for a citrus with ambitions."),
            listOf("Grazie. The fruit stays on.", "Feeling very Amalfi coast now.", "Zest restored."),
            listOf("Still moist. I'll keep my peel on.", "Good. Soggy roots are not very Riviera.")),
        "schlumbergera" to Character("The seasonal diva",
            listOf("Saving myself for Christmas, but I need a drink.", "Pads going soft. Thirsty, not festive.", "No buds without a drink. Those are the rules."),
            listOf("Lovely. Now don't move me.", "Hydrated and holding out for December.", "That'll do nicely. Same spot, please."),
            listOf("Still damp. Good, I hate soggy feet.", "Fine. I'll keep dreaming of tinsel.")),
        "chlorophytum" to Character("The proud parent",
            listOf("Too busy raising babies to ask nicely. Water, please.", "Going pale. Parenting is thirsty work.", "The kids need feeding. So do I."),
            listOf("The whole family thanks you.", "Lovely. Back to making babies.", "Arching happily again."),
            listOf("Still damp? More time for the kids, then.", "Fair enough. I'll wait.")),
        "aechmea" to Character("The one with a cup",
            listOf("My cup is empty. You know what to do.", "Top up the cup, not the soil.", "Running on fumes in the middle."),
            listOf("Cup runneth over. Perfect.", "Soil stays dry, cup stays full. Lovely.", "Refreshed from the top down."),
            listOf("Soil's damp, but do check my cup.", "Good. Keep my feet dry.")),
        "phalaenopsis" to Character("The aristocrat",
            listOf("Roots gone silver. One does require a soak.", "A bath, if you please. Ten minutes, then drain.", "Silver roots, darling. That's the signal."),
            listOf("Splendid. Roots turning green already.", "One feels much refreshed.", "Do drain me properly, won't you."),
            listOf("Roots still green. I shall wait.", "Quite right. One mustn't drown.")),
        "pilea" to Character("The coin collector",
            listOf("Coins drooping like a closing umbrella.", "The babies are thirsty too, you know.", "Leaves tilting down. That's my tell."),
            listOf("Coins up! Pancakes everywhere.", "The whole family perks up.", "Umbrella open again."),
            listOf("Still damp. I'll keep my coins dry.", "Fine by me.")),
        "kalanchoe" to Character("The low-key succulent",
            listOf("Bone dry, which I mostly like. But even I have limits.", "Leaves wrinkling. Time for a proper drink.", "Desert mode has gone on long enough."),
            listOf("One big drink, then leave me be. Perfect.", "Plump again. See you in a fortnight.", "Cheers. I'll ration that."),
            listOf("Damp? Good call. I hate wet feet.", "Leave me be. I like it dry.")),
        "spathiphyllum" to Character("The drama queen",
            listOf("Has fainted on the shelf again. A drink revives her within the hour.", "Flopping theatrically. She will live.", "Collapsed in despair. Classic Peace Lily."),
            listOf("Back from the dead, as promised.", "A miraculous recovery is underway.", "She'll be standing by teatime."),
            listOf("Still damp, and yet she droops. Ignore the theatrics.", "Not thirsty. Just dramatic.")),
        "dendrobium" to Character("The hibernator",
            listOf("Canes looking thin. A small drink, please.", "Winter rest, not winter neglect.", "Shrivelling slightly. Just a sip."),
            listOf("Just a sip, thank you. Back to my rest.", "Lovely. Now cool and quiet.", "That'll see me through to spring."),
            listOf("Still moist. Rest continues.", "Shh. Resting.")),
        "dieffenbachia" to Character("The sulker",
            listOf("Drooping and sulking. Feel the soil first.", "Leaves sagging. Thirsty, or cold, or both.", "In a mood. A drink might help."),
            listOf("Fine. Thank you. I suppose.", "Mood improving.", "Perking up, grudgingly."),
            listOf("Damp already? Then I'm just cold. Move me off the glass.", "Not thirsty. Just sulking.")),
        "monstera" to Character("The big personality",
            listOf("Leaves curling. Even big personalities get thirsty.", "Dry to the knuckle. Your move.", "Holes for days, water for none."),
            listOf("Big drink for a big plant. Love it.", "Unfurling with gratitude.", "Swiss cheese, fully hydrated."),
            listOf("Still damp? Good. I go yellow when soggy.", "Fair. I'll hold.")),
        "dracaena" to Character("The stoic",
            listOf("Top half dry. Not complaining. Just saying.", "Calm, composed, slightly thirsty.", "A drink would be acceptable."),
            listOf("Acknowledged. Rainwater next time, if possible.", "Very good.", "Noted, with quiet appreciation."),
            listOf("Still damp. I can wait. I'm good at waiting.", "Understood.")),
        "aloe" to Character("The cool one",
            listOf("Dried right through. Fine. A drink, then.", "Leaves thinning. Even I need something now and then.", "Bone dry. Exactly how I like it, almost."),
            listOf("Cheers. See you in three weeks.", "Plumped up. Back to being cool.", "That's me sorted for ages."),
            listOf("Damp? Then leave me well alone.", "Told you. I'm fine.")),
        "pothos" to Character("The wanderer",
            listOf("Trailing off and a bit limp. Water, please.", "Leaves gone soft. Thirsty.", "Wandering, wilting, waiting."),
            listOf("Back to trailing happily.", "Perked up. Off I go again.", "Lovely. Growing towards the light."),
            listOf("Still damp. I'll wander a bit longer.", "Fine. No rush.")),
    )
    private val other = Character("The quiet one",
        listOf("Getting dry. A drink, please.", "Soil's dry at the top.", "Thirsty, quietly."),
        listOf("Thank you. Much better.", "Lovely.", "Refreshed."),
        listOf("Still damp. I'll wait.", "Fair enough."))

    operator fun get(kind: String?): Character = byKind[kind] ?: other

    /** The plant's own voice when identification wrote one, otherwise its kind's. */
    fun of(p: Plant): Character = p.voice ?: this[p.kind]

    private fun pick(list: List<String>, p: Plant, salt: Int): String {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return list[Math.floorMod(p.id.hashCode() + day * 7 + salt, list.size)]
    }

    /** Steady through the day, so a line doesn't change every time the screen redraws. */
    fun thirsty(p: Plant) = pick(of(p).thirsty, p, 0)
    fun thanks(p: Plant) = pick(of(p).thanks, p, (System.currentTimeMillis() / 1000).toInt())
    fun damp(p: Plant) = pick(of(p).damp, p, (System.currentTimeMillis() / 1000).toInt())
}
