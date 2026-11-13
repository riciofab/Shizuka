package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneManageTest {
    private val tv = Category("default", "TV", listOf("free", "hdmi:1", "molotov"), size = "large", belowWatch = true)
    private val films = Category("cat_1", "Films", listOf("netflix", "prime"), icon = "film")
    private val known = setOf("free", "molotov", "netflix", "prime", "youtube", "hdmi:1")
    private fun edit(vararg rows: ManagedRow) = managedCategories(listOf(tv, films), rows.toList(), known, { it.startsWith("hdmi:") }) { "new_$it" }

    @Test fun aRenamedAndReorderedCategoryKeepsItsLook() {
        val out = edit(ManagedRow("cat_1", " Cinéma ", listOf("prime", "netflix")), ManagedRow("default", "TV", listOf("molotov", "free")))!!
        assertEquals(listOf("cat_1", "default"), out.map { it.id }) // the phone's order of categories
        assertEquals(films.copy(name = "Cinéma", apps = listOf("prime", "netflix")), out[0])
        // What the phone can't see stays: the row's size and place, and its pinned HDMI input at the end.
        assertEquals(tv.copy(apps = listOf("molotov", "free", "hdmi:1")), out[1])
    }

    @Test fun anAppIsInOneCategoryAndOnlyKnownAppsComeIn() {
        val out = edit(ManagedRow("default", "TV", listOf("free", "netflix", "gone", "hdmi:9")), ManagedRow("cat_1", "Films", listOf("netflix", "prime")))!!
        assertEquals(listOf("free", "netflix", "hdmi:1"), out[0].apps) // "gone" is uninstalled; the phone can't add an input
        assertEquals(listOf("prime"), out[1].apps) // netflix was already placed
    }

    @Test fun newCategoriesGetAnIdAndNamelessOnesAreDropped() {
        val out = edit(ManagedRow("", "Musique", listOf("youtube")), ManagedRow("", "  ", listOf("free")), ManagedRow("default", "", listOf("molotov")))!!
        assertEquals(listOf(Category("new_0", "Musique", listOf("youtube")), tv.copy(apps = listOf("molotov", "hdmi:1"))), out)
    }

    @Test fun theSameCategoryTwiceIsANewOneTheSecondTime() {
        val out = edit(ManagedRow("cat_1", "Films", listOf("netflix")), ManagedRow("cat_1", "Séries", listOf("prime")))!!
        assertEquals(listOf("cat_1", "new_1"), out.map { it.id })
    }

    @Test fun theHomeScreenKeepsAtLeastOneCategory() {
        assertNull(edit())
        assertNull(edit(ManagedRow("", "", emptyList())))
    }
}
