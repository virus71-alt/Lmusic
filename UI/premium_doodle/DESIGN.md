---
name: Premium Doodle
colors:
  surface: '#fcf9f8'
  surface-dim: '#dcd9d9'
  surface-bright: '#fcf9f8'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f6f3f2'
  surface-container: '#f0eded'
  surface-container-high: '#eae7e7'
  surface-container-highest: '#e4e2e1'
  on-surface: '#1b1c1c'
  on-surface-variant: '#50453d'
  inverse-surface: '#303030'
  inverse-on-surface: '#f3f0f0'
  outline: '#82746c'
  outline-variant: '#d4c3ba'
  surface-tint: '#7a573e'
  primary: '#7a573e'
  on-primary: '#ffffff'
  primary-container: '#f6c7a8'
  on-primary-container: '#745139'
  inverse-primary: '#ebbd9f'
  secondary: '#356668'
  on-secondary: '#ffffff'
  secondary-container: '#b9ecee'
  on-secondary-container: '#3c6c6e'
  tertiary: '#7c5357'
  on-tertiary: '#ffffff'
  tertiary-container: '#f9c3c7'
  on-tertiary-container: '#764e52'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#ffdcc5'
  primary-fixed-dim: '#ebbd9f'
  on-primary-fixed: '#2e1503'
  on-primary-fixed-variant: '#604028'
  secondary-fixed: '#b9ecee'
  secondary-fixed-dim: '#9ecfd1'
  on-secondary-fixed: '#002021'
  on-secondary-fixed-variant: '#1a4e50'
  tertiary-fixed: '#ffdadc'
  tertiary-fixed-dim: '#eeb9bd'
  on-tertiary-fixed: '#301216'
  on-tertiary-fixed-variant: '#623c40'
  background: '#fcf9f8'
  on-background: '#1b1c1c'
  surface-variant: '#e4e2e1'
typography:
  display-lg:
    fontFamily: Bricolage Grotesque
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 52px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Bricolage Grotesque
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-lg-mobile:
    fontFamily: Bricolage Grotesque
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  title-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-caps:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.1em
  doodle-accent:
    fontFamily: Bricolage Grotesque
    fontSize: 20px
    fontWeight: '400'
    lineHeight: 24px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 8px
  xs: 4px
  sm: 12px
  md: 24px
  lg: 40px
  xl: 64px
  container-margin: 20px
  gutter: 16px
---

## Brand & Style
The design system for this mobile music application bridges the gap between organic human expression and high-end digital precision. It is designed for a Gen Z audience that values authenticity, tactile interfaces, and a "lo-fi study beats" aesthetic. 

The style is defined as **Liquid Sketch**: a fusion of hand-drawn editorial linework and high-fidelity Glassmorphism. The interface should feel like a premium digital notebook—sophisticated yet approachable. We utilize thin, varying-width strokes that mimic ink on paper, paired with "liquid glass" surfaces that provide depth and modern SaaS polish. The emotional response should be one of calm, creative inspiration, and high-quality craftsmanship.

## Colors
The palette uses warm, desaturated tones to create a soothing, premium environment. 
- **Primary (Soft Peach):** Used for main actions and active states.
- **Secondary (Powder Blue):** Used for interactive secondary elements and subtle glass tints.
- **Accent (Soft Rose):** Reserved for decorative doodles, highlights, and emotional micro-interactions.
- **Neutral (Dark Charcoal):** Used for all high-contrast linework and text to maintain readability against the creamy background.
- **Surfaces:** The background uses a warm off-white (#FFF8F0), while elevated cards use a crisp, bright white (#FFFDF8) to create a subtle layered effect without harsh shadows.

## Typography
Typography is a mix of high-functionality and expressive personality.
- **Headlines:** Use **Bricolage Grotesque**. Its quirky, slightly irregular terminals reflect the "doodle" aesthetic while remaining professional and structured.
- **Body:** **Inter** is the workhorse, providing maximum legibility for track titles, artist names, and settings.
- **Labels/Technical:** **JetBrains Mono** is used for timestamps and metadata (e.g., "320kbps") to introduce a clean, technical contrast to the organic shapes.
- **Doodle Accents:** Use the italicized weight of Bricolage Grotesque or a custom hand-drawn font for "asides," such as handwritten song lyrics or floating notes.

## Layout & Spacing
This design system utilizes a **Fluid Grid** model with a soft, organic rhythm. 
- **The 8px Grid:** All spacing is based on 8px increments, but elements should rarely feel "boxed in." 
- **Safe Margins:** A generous 20px margin on mobile ensures that "floating" doodle elements (like stars or music notes) can bleed into the margins without obscuring critical UI.
- **Reflow:** On tablets, the layout expands to a 2-column view (sidebar for navigation, main view for content), maintaining the 24px gutter.
- **Asymmetry:** Occasional intentional misalignment (1-2px) of decorative borders is encouraged to enhance the hand-drawn feel.

## Elevation & Depth
Depth is achieved through **Soft Neumorphism** and **Liquid Glass** layers:
- **Level 0 (Background):** #FFF8F0 flat surface.
- **Level 1 (Glass Cards):** Frosted glass panels (Background Blur: 20px) with 40% opacity white fill. These panels feature a 1px Dark Charcoal (#2D2D2D) border that looks like a thin pen stroke.
- **Level 2 (Interactive Elements):** Buttons and active cards use a dual-shadow approach. A soft, light-colored drop shadow (Primary color at 20% opacity) combined with a 1px solid sketch-outline.
- **Floating Layer:** Floating icons (notes, stars) utilize a very soft, high-blur shadow to appear as if they are hovering 10mm above the screen.

## Shapes
The shape language is "Organic Geometric." While containers follow a standard **0.5rem (8px)** base roundedness, the actual visible borders should use SVG paths to create a "live stroke" effect—slightly imperfect lines that don't perfectly meet at the corners. 

- **Interactive Elements:** Use `rounded-lg` (16px) for a soft, friendly feel.
- **Play/Pause Buttons:** Use `rounded-xl` or perfect circles to stand out.
- **Progress Bars:** Use organic "bean" shapes rather than sharp-edged capsules.

## Components
- **Doodle Buttons:** Primary buttons feature a solid fill of #F6C7A8 with a 1.5pt charcoal outline. Upon press, the button should "squish" (Scale Y: 0.95) and the shadow should disappear to simulate physical pressure.
- **Glass Cards:** Used for album art containers. They feature a high backdrop blur and a hand-drawn "corner bracket" doodle in the Accent color.
- **Neumorphic Sliders:** The track (seek bar) is a recessed "well" in the UI. The handle (scrubber) is a solid, peach-colored circle with a hand-drawn "sparkle" icon on top.
- **Input Fields:** Minimalist underlines with a "scribble" animation when focused.
- **Floating Icons:** Small decorative SVGs (stars, musical notes) should have a subtle "float" animation (±4px Y-axis) to bring the "notebook" to life.
- **Selection Controls:** Checkboxes and radio buttons should look like hand-drawn "X" marks or circled items.