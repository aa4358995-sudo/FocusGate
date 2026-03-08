package com.focusgate.utils

/**
 * Generates random complex paragraphs for the Emergency Exit protocol.
 * The phrases are intentionally verbose to create meaningful friction,
 * deterring impulsive exits while remaining completable in a true emergency.
 */
object EmergencyPhraseGenerator {

    private val phrases = listOf(
        "The persistent nature of digital distraction requires intentional countermeasures; by pausing to manually transcribe this particular passage, you confirm that your desire to exit this focus session is deliberate, considered, and not merely the product of momentary impulse or habitual reflexive behavior.",
        "Conscious awareness of one's attention is the foundational principle behind this application; transcribing these words confirms that you are making a fully informed, autonomous decision to end your current session rather than surrendering to an unconscious dopamine-seeking behavioral pattern.",
        "Mindful technology use demands that we periodically confront the automated nature of our digital habits; completing this transcription is an act of metacognition, a deliberate pause that transforms an impulsive desire into a conscious, considered, and intentional choice about how to spend your time.",
        "The boundary between intentional and habitual phone usage is often invisible in the moment; by completing this exit protocol you are demonstrating that your need to leave this focused environment is genuine, purposeful, and worthy of the deliberate effort this passage requires.",
        "Breaking from a concentrated state of focus carries a cognitive cost that is rarely appreciated in the moment of distraction; this phrase exists to make that cost tangible, visible, and worthy of your consideration before you decide to abandon the productive session you deliberately initiated.",
        "Every interruption to deep focus work carries a measurable productivity penalty that accumulates across the working day; typing this passage is your acknowledgment that you understand this cost and have decided, after careful reflection, that your reason for exiting is more important than your current session.",
        "The Zeigarnik effect suggests that uncompleted tasks occupy mental bandwidth disproportionately; by completing this passage you are demonstrating executive function over the impulsive parts of your cognition, and proving that your exit is the product of reason rather than the automatic seeking of novel stimulation.",
        "Sustained attention is among the most valuable cognitive resources available to a human being, and it is systematically eroded by environments engineered to exploit neurological reward pathways; this transcription represents your considered assertion that the value of exiting now exceeds the value of the focus you would otherwise preserve."
    )

    fun generate(): String = phrases.random()
}
