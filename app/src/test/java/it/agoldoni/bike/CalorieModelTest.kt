package it.agoldoni.bike

import org.junit.Assert.assertEquals
import org.junit.Test

class CalorieModelTest {

    @Test
    fun `il MET sale a scatti con la velocita`() {
        assertEquals(4.0f, CalorieModel.metFor(12f), 0f)
        assertEquals(6.8f, CalorieModel.metFor(16f), 0f)
        assertEquals(8.0f, CalorieModel.metFor(20f), 0f)
        assertEquals(10.0f, CalorieModel.metFor(24f), 0f)
        assertEquals(12.0f, CalorieModel.metFor(28f), 0f)
        assertEquals(15.8f, CalorieModel.metFor(35f), 0f)
    }

    @Test
    fun `da fermi il MET e zero`() {
        // Il caso dei semafori: senza questa soglia un giro cittadino conterebbe
        // calorie anche nei minuti di sosta.
        assertEquals(0f, CalorieModel.metFor(0f), 0f)
        assertEquals(0f, CalorieModel.metFor(2.9f), 0f)
    }

    @Test
    fun `un ora a 20 km orari brucia MET per massa`() {
        // 8.0 MET × 87 kg × 1 h
        assertEquals(696f, CalorieModel.kcalFor(20f, 3_600_000L, 87f), 0.01f)
    }

    @Test
    fun `le calorie scalano col tempo`() {
        assertEquals(11.6f, CalorieModel.kcalFor(20f, 60_000L, 87f), 0.01f)
    }

    @Test
    fun `un intervallo nullo o negativo non produce calorie`() {
        assertEquals(0f, CalorieModel.kcalFor(20f, 0L, 87f), 0f)
        assertEquals(0f, CalorieModel.kcalFor(20f, -1_000L, 87f), 0f)
    }
}
