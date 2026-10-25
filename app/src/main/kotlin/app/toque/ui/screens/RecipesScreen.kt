package app.toque.ui.screens

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.RecipeFilter
import app.toque.core.recipes.SearchIndex
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.util.concurrent.Executors

/**
 * Catalogue des recettes Lidl : recherche instantanée tolérante (accents, pluriels, fautes de
 * frappe), filtres, et recherche par ingrédients (séparés par des virgules).
 */
class RecipesScreen(a: MainActivity, private val focusSearch: Boolean = false, favorites: Boolean = false) : Screen(a) {
    override val title = if (favorites) tr("Favoris") else tr("Recettes")

    private var filter = RecipeFilter(favoritesOnly = favorites)
    private var query = ""
    private var results: List<SearchIndex.Hit> = emptyList()
    private var generation = 0
    private lateinit var input: EditText
    private lateinit var chips: LinearLayout
    private lateinit var grid: GridView
    private lateinit var count: TextView
    private lateinit var empty: LinearLayout
    private lateinit var emptyText: TextView
    private val adapter = Adapter()
    private var unsub: (() -> Unit)? = null
    private val runSearch = Runnable { search() }

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), 0)

        val bar = Ui.horizontal(c)
        val field = Ui.horizontal(c)
        field.setPadding(Ui.u(20), 0, Ui.u(10), 0)
        field.background = Ui.round(Ui.SURFACE, 32f, Ui.STROKE, 1f)
        field.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(12) })
        input = EditText(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(21f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            hint = tr("Recette, ingrédient… (ex. : poulet, curry)")
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a1: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString() ?: ""
                    view.removeCallbacks(runSearch)
                    view.postDelayed(runSearch, 120)
                }
            })
            setOnEditorActionListener { _, _, _ -> a.hideKeyboard(); true }
        }
        field.addView(input, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))
        val clear = Icon(c, Icon.Kind.CLOSE, Ui.TEXT2)
        clear.setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        clear.setOnClickListener { input.setText("") }
        field.addView(clear, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
        bar.addView(field, LinearLayout.LayoutParams(0, Ui.u(64), 1f))
        count = Ui.text(c, "", 17f, Ui.TEXT2)
        bar.addView(count, Ui.lp(Ui.WRAP, Ui.WRAP, l = 18))
        root.addView(bar)

        val scroller = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        chips = Ui.horizontal(c)
        scroller.addView(chips)
        root.addView(scroller, Ui.lp(t = 12, b = 12))

        val body = FrameLayout(c)
        grid = GridView(c).apply {
            numColumns = 4
            horizontalSpacing = Ui.u(14)
            verticalSpacing = Ui.u(14)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = android.graphics.drawable.ColorDrawable(0)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, Ui.u(20))
            clipToPadding = false
            adapter = this@RecipesScreen.adapter
            setOnItemClickListener { _, _, pos, _ ->
                val hit = results.getOrNull(pos) ?: return@setOnItemClickListener
                val r = a.app.recipes.get(hit.entry.id)
                if (r != null) a.push(RecipeScreen(a, r))
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) {
                    if (state != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) a.hideKeyboard()
                }
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }
        body.addView(grid, Ui.flp())
        empty = Ui.vertical(c, Gravity.CENTER)
        empty.addView(Icon(c, Icon.Kind.BOOK, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(64), Ui.u(64)))
        emptyText = Ui.text(c, "", 21f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        empty.addView(emptyText, Ui.lp(Ui.u(640), Ui.WRAP, t = 16))
        body.addView(empty, Ui.flp())
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        return root
    }

    private fun buildChips() {
        chips.removeAllViews()
        fun chip(label: String, selected: Boolean, f: RecipeFilter) {
            chips.addView(Ui.toggleChip(c, label, selected) { filter = f; buildChips(); search() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        }
        val base = RecipeFilter()
        chip(tr("Toutes"), filter == base, base)
        chip(tr("Favoris"), filter.favoritesOnly, base.copy(favoritesOnly = true))
        chip(tr("30 min max"), filter.maxMinutes == 30, base.copy(maxMinutes = 30))
        for (cat in a.app.recipes.index.categories.take(30)) chip(cat, filter.category == cat, base.copy(category = cat))
    }

    private fun search() {
        val gen = ++generation
        val q = query
        val f = filter
        val index = a.app.recipes.index
        searchExec.execute {
            val hits = if (q.contains(',')) index.withIngredients(q.split(','), f) else index.search(q, f)
            view.post {
                if (gen != generation) return@post
                results = hits
                adapter.notifyDataSetChanged()
                refreshEmpty()
            }
        }
    }

    private fun refreshEmpty() {
        val repo = a.app.recipes
        val none = repo.loaded && repo.count == 0
        count.text = if (results.isEmpty()) "" else if (results.size == 1) tr("1 recette") else tr("%d recettes", results.size)
        empty.visibility = if (results.isEmpty() && repo.loaded) View.VISIBLE else View.GONE
        emptyText.text = when {
            none && a.prefs.mode == Prefs.Mode.HARDWARE ->
                tr("Les recettes Lidl apparaîtront après la première synchronisation.\nRéglages › Recettes Lidl › Synchroniser.")
            none -> tr("Les recettes Lidl apparaîtront après la première synchronisation, une fois Toque installé sur le robot.")
            filter.favoritesOnly && query.isBlank() -> tr("Aucun favori pour l'instant.\nTouchez l'étoile d'une recette pour l'ajouter.")
            else -> tr("Aucune recette ne correspond.\nAstuce : séparez plusieurs ingrédients par des virgules.")
        }
    }

    override fun onShow() {
        buildChips()
        search()
        unsub = a.app.recipes.onChange { buildChips(); search() }
        if (focusSearch) view.postDelayed({
            input.requestFocus()
            (c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    override fun onHide() {
        unsub?.invoke()
    }

    override fun onKnob(delta: Int): Boolean {
        grid.smoothScrollBy(delta * Ui.u(240), 150)
        return true
    }

    private companion object {
        /** Un seul fil de recherche partagé par tous les écrans Recettes. */
        val searchExec = Executors.newSingleThreadExecutor()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = results.size
        override fun getItem(position: Int) = results[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder = (convertView?.tag as? Holder) ?: Holder(c)
            holder.bind(results[position].entry)
            return holder.root
        }
    }

    private inner class Holder(c: Context) {
        val root = Ui.vertical(c)
        val imageBox = FrameLayout(c)
        val image = ImageView(c)
        val initials = Ui.text(c, "", 44f, 0x55FFFFFF, Ui.THIN)
        val title = Ui.ellipsize(Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM), 2)
        val meta = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT2))
        val star = Icon(c, Icon.Kind.STAR_FILLED, Ui.ACCENT)

        init {
            root.tag = this
            root.background = Ui.round(Ui.SURFACE, 22f)
            root.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, Ui.u(232))
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            imageBox.addView(initials, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
            imageBox.addView(image, Ui.flp())
            imageBox.addView(star, Ui.flp(Ui.u(26), Ui.u(26), Gravity.TOP or Gravity.END).apply { setMargins(0, Ui.u(10), Ui.u(10), 0) })
            imageBox.clipToOutline = true
            imageBox.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height + Ui.u(22), Ui.uf(22f))
                }
            }
            root.addView(imageBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(128)))
            root.addView(title, Ui.lp(l = 14, t = 10, r = 12))
            root.addView(meta, Ui.lp(l = 14, t = 4, r = 12))
        }

        fun bind(e: SearchIndex.Entry) {
            title.text = e.title
            val parts = ArrayList<String>()
            if (e.ratingCount > 0) parts += "★ " + String.format(I18n.locale, "%.1f", e.rating)
            if (e.totalMinutes > 0) parts += "${e.totalMinutes} min"
            if (e.category.isNotBlank()) parts += e.category
            meta.text = parts.joinToString(" · ")
            star.visibility = if (a.app.recipes.isFavorite(e.id)) View.VISIBLE else View.GONE
            imageBox.background = Ui.gradient(Ui.placeholderColors(e.title), 0f)
            initials.text = Ui.initials(e.title)
            ImageLoader.load(image, a.app.recipes.imageFile(e.image), Ui.u(260))
        }
    }
}
