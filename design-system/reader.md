# Reader visual and interaction rules

The existing native Android View interface stays focused on opening and reading
documents. UI UX Pro Max's verified Minimalism & Swiss Style guidance informs
the spacing, hierarchy and restrained chrome. Its generated enterprise gateway
pattern did not fit this reader and was discarded.

- Home has one open-file button, with a decorative folder icon and one label.
- White paper stays edge to edge at fit width. Chrome uses white surfaces and a
  cool neutral background; no side margins are added around the document.
- Separate adjacent pages with an 8dp gray gap at fit width, scaled with the
  document during zoom. Draw it only between pages, never as a side frame.
- Interface text uses Android's system sans, 16sp medium for filename and action,
  15sp for headings, 12sp for page metadata. Document typography is unchanged.
- Colors live in `res/values/colors.xml`: ink #243847, muted #566875, teal
  #216B60, mint #80D4BA, selected #E1F3ED, background #F5F8FA.
- All toolbar icons use the same adapted IconPark family, 48-unit viewport,
  3.4-unit round strokes, rendered at 26dp inside 48dp Android touch targets.
- Reset uses the curved return arrow; fit text uses the horizontal width arrows.
  These are separate actions. Both respect the size lock. A spinner replaces
  the fit glyph during analysis, and a selected border identifies the fitted view.
- Five bottom actions keep 48dp targets with 8dp gaps. If the page badge cannot
  fit beside them, it moves below at the lower right. Very narrow split-screen
  windows wrap the actions too, preserving their touch targets.
- Spacing follows 4/8dp increments. Controls use 14dp corners; the home action
  and drawer use 20dp. No decorative hero, slogans or feature descriptions.
- Icon controls have accessibility names, native ripple and keyboard focus
  borders. Selection uses a border or marker plus color and native selected
  semantics. The locked and unlocked glyphs differ in shape.
- Drawer entry uses a short 180ms opacity/translation transition only when
  system animators are enabled. Scrolling and document scaling remain direct.
- Outline rows show actual headings and destination page numbers; previews
  mark the current page with a teal border. No explanatory captions are added.
- This release deliberately keeps the app's existing light reading theme,
  including under system dark mode; Android automatic color inversion is off.
- Icons are local vector resources. No fonts, packages or network permissions
  are added for this visual update.
