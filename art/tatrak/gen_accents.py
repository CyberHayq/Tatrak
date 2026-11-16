# Usage: python3 art/tatrak/gen_accents.py src/main/res
# Generates res/values{,-night}/tatrak_accent_colors.xml, res/values/tatrak_accent_themes.xml, res/values/tatrak_attrs.xml and
# kotlin/am/cyberhayq/chat/ui/theme/Neutrals.kt (neutral surfaces of the Slate and Sea style and of the night backgrounds)
import os,sys
res=sys.argv[1]
def h(c): return tuple(int(c[i:i+2],16) for i in (0,2,4))
def x(t): return '%02X%02X%02X'%tuple(max(0,min(255,round(v))) for v in t)
def mix(a,b,t): a,b=h(a),h(b); return x(tuple(a[i]+(b[i]-a[i])*t for i in range(3)))
W,K,DARK_TEXT,NIGHT_BG,NIGHT_DEEP='FFFFFF','000000','1A1A1A','1D2733','0E1621'
ROLES=['primary','on_primary','primary_container','on_primary_container','secondary','secondary_container','on_secondary_container','link','bubble_out','time_out','check_read','badge','accent_in','accent_out']
def light(s): return dict(primary=s,on_primary=W,primary_container=mix(s,K,0.3),on_primary_container=W,secondary=mix(s,W,0.25),secondary_container=mix(s,W,0.8),on_secondary_container=mix(s,K,0.6),link=mix(s,K,0.1),bubble_out=mix(s,W,0.86),time_out=mix(s,K,0.1),check_read=s,badge=s,accent_in=s,accent_out=mix(s,K,0.05))
def dark(s): p=mix(s,W,0.25); return dict(primary=p,on_primary=W,primary_container='212D3B',on_primary_container=W,secondary=p,secondary_container=mix(s,NIGHT_BG,0.6),on_secondary_container=W,link=mix(s,W,0.4),bubble_out=mix(s,NIGHT_DEEP,0.55),time_out=mix(s,W,0.45),check_read=mix(s,W,0.3),badge=mix(s,NIGHT_DEEP,0.35),accent_in=mix(s,W,0.3),accent_out=mix(s,W,0.3))
# the nine accents (owner, 2026-11-15), in picker order; the seed is also the picker swatch (Accent.swatch)
ACC=[('blue','298ACF'),('cyan','1EA6B6'),('sea','0B6F7A'),('green','3FA34D'),('violet','8E63CE'),('pink','E05A8D'),('orange','EB8A2F'),('red','D9534F'),('gold','D4A017')]
def lum(c): return sum(w*(v/12.92 if v<=.03928 else ((v+.055)/1.055)**2.4) for w,v in zip((.2126,.7152,.0722),(t/255 for t in h(c))))
def cr(a,b): p,q=sorted((lum(a),lum(b)),reverse=True); return (p+.05)/(q+.05)
def fit(c,bg,ratio,toward=K):  # c nudged toward `toward` until it reaches `ratio` contrast on bg (WCAG AA body text: 4.5)
    return fit_all(c,[bg],ratio,toward)
def fit_all(c,bgs,ratio,toward):  # the same against every colour in bgs
    t=0
    while min(cr(mix(c,toward,t),b) for b in bgs)<ratio and t<1: t+=.01
    return mix(c,toward,t)
def fit_tint(c,bgs,alpha,ratio,toward):  # c against itself laid at `alpha` over each bg (a tinted chip or pill carrying c)
    t=0
    while min(cr(mix(c,toward,t),mix(b,mix(c,toward,t),alpha)) for b in bgs)<ratio and t<1: t+=.01
    return mix(c,toward,t)
def glyph(fill): return DARK_TEXT if lum(fill)>0.30 else W  # contentOn() in Theme.kt: white >= 3:1 below, near-black >= 5.8:1 above
L={a:light(s) for a,s in ACC}; D={a:dark(s) for a,s in ACC}
# blue = the Compose colours the app shipped with (the fit pass below still adjusts the slots that fail contrast)
L['blue'].update(primary='298ACF',primary_container='527DA3',secondary='65A9E0',link='2678B6',bubble_out='EFFFDE',time_out='6EB869',check_read='46AA36',badge='229AF0',accent_in='3A8DDA',accent_out='59B159')
D['blue'].update(primary='5DA7E8',secondary='5DA7E8',link='71BAFA',bubble_out='2B5278',time_out='7DA8D3',check_read='64B5EF',badge='3E6A99',accent_in='64B5EF',accent_out='5FA6E6')
# Sea (default accent of the Slate and Sea style): teal seed, dark text on the light night primary, hand-picked bubbles
L['sea'].update(bubble_out='CFE9EC',time_out='3A585C')
D['sea'].update(primary='5CC4CE',on_primary='0B1F22',primary_container='12414A',secondary='5CC4CE',link='7FD6DE',bubble_out='12414A',time_out='A9CED3',check_read='7FD6DE',badge='0B6F7A',accent_in='7FD6DE',accent_out='7FD6DE')

# Neutral surfaces. XML: ThemeOverlay.Tatrak (NEUTRAL_L / NEUTRAL_D; its containers mirror LightScheme / DarkScheme), Compose: the defaults in Theme.kt.
NEUTRAL_L=dict(background='FFFFFF',on_background='1A1D21',surface='FFFFFF',on_surface='1A1D21',surface_variant='F1F1F1',on_surface_variant='6C6E70',outline='C4C7CA',
        container_lowest='FFFFFF',container='FFFFFF',container_highest='F1F1F1',outline_variant='D9D9D9')
NEUTRAL_D=dict(background='1D2733',on_background='E9EDF0',surface='1D2733',on_surface='E9EDF0',surface_variant='242F3D',on_surface_variant='93A0AC',outline='3A4756',
        container_lowest='17202B',container='242F3D',container_highest='2B3746',outline_variant='10171F')
# The Telegram style without neutrals (day, and the blue-grey night): mirrors LightChatColors / LightScheme and
# DarkChatColors / DarkScheme in Theme.kt (keep in sync); not written anywhere, only used to fit accents and by check_contrast.py.
TELEGRAM_L=dict(background='FFFFFF',top_bar='FFFFFF',bubble_in='FFFFFF',chat_background='CBD9C0',container='FFFFFF',container_highest='F1F1F1',surface_variant='F1F1F1',
        text='1A1D21',on_surface_variant='6C6E70',on_top_bar_muted='747678',time_in='71777D')
TELEGRAM_D=dict(background='1D2733',top_bar='212D3B',bubble_in='182533',chat_background='0E1621',container='242F3D',container_highest='2B3746',surface_variant='242F3D',
        text='E6E0E9',on_surface_variant='96A0A9',on_top_bar_muted='8D9BA8',time_in='7D8D9B')
# Slate and Sea style (see design_draft.md): cool slate surfaces; ground = background, raised = bars / incoming bubbles / sheets
SLATE_L=dict(background='EEF1F4',on_background='14191F',surface='EEF1F4',on_surface='14191F',surface_variant='E3E9EE',on_surface_variant='56616C',outline='7C8791',
        top_bar='F8FAFB',on_top_bar='14191F',on_top_bar_muted='56616C',drawer_header='2B3A44',archive='7F8C98',chat_background='E3E9EE',bubble_in='F8FAFB',
        time_in='56616C',text='14191F',service='B3'+'26323B',divider='F8FAFB',outline_variant='DCE2E8',container_lowest='F8FAFB',container='F8FAFB',container_highest='DCE2E8',online='2FA85A')
SLATE_D=dict(background='0F1316',on_background='E6EAEE',surface='0F1316',on_surface='E6EAEE',surface_variant='1F262B',on_surface_variant='98A3AD',outline='5E6B76',
        top_bar='171C20',on_top_bar='E6EAEE',on_top_bar_muted='98A3AD',drawer_header='1F262B',archive='3A444D',chat_background='0F1316',bubble_in='1F262B',
        time_in='98A3AD',text='E6EAEE',service='CC1F262B',divider='1F262B',outline_variant='232B31',container_lowest='0F1316',container='171C20',container_highest='2A323A',online='4CC38A')
# Night background "graphite", Telegram style: calm charcoal layers and off-white text
GRAPHITE=dict(background='121214',on_background='ECE9E6',surface='121214',on_surface='ECE9E6',surface_variant='1C1C1F',on_surface_variant='A7A4A1',outline='616165',
        top_bar='1A1A1D',on_top_bar='ECE9E6',on_top_bar_muted='A7A4A1',drawer_header='222226',archive='3A3A3E',chat_background='0B0B0D',bubble_in='1E1E22',
        time_in='8E8B88',text='ECE9E6',service='CC1E1E22',divider='1E1E22',outline_variant='2A2A2E',container_lowest='0B0B0D',container='1C1C1F',container_highest='2A2A2E')
# Night background "graphite", Slate and Sea: graphite with a light cool tint
SLATE_GRAPHITE=dict(background='111315',on_background='E8EBED',surface='111315',on_surface='E8EBED',surface_variant='1B1E21',on_surface_variant='A0A6AB',outline='5F666D',
        top_bar='181B1E',on_top_bar='E8EBED',on_top_bar_muted='A0A6AB',drawer_header='1E2124',archive='3A3F44',chat_background='0B0C0E',bubble_in='1E2124',
        time_in='9399A0',text='E8EBED',service='CC1E2124',divider='1E2124',outline_variant='25292D',container_lowest='0B0C0E',container='181B1E',container_highest='2A2E32',online='4CC38A')
# Night background "AMOLED black": pure black ground and chat, raised layers (bars, bubbles, sheets) and lines light enough to read on black.
# One neutral set for both styles; Slate and Sea only keeps its own online green.
AMOLED=dict(background='000000',on_background='E8E8E8',surface='000000',on_surface='E8E8E8',surface_variant='1A1A1A',on_surface_variant='A0A0A0',outline='666666',
        top_bar='121212',on_top_bar='E8E8E8',on_top_bar_muted='A0A0A0',drawer_header='1A1A1A',archive='3A3A3A',chat_background='000000',bubble_in='1A1A1A',
        time_in='999999',text='E8E8E8',service='CC262626',divider='1A1A1A',outline_variant='2E2E2E',container_lowest='000000',container='1A1A1A',container_highest='262626')
SLATE_AMOLED={**AMOLED,'online':'4CC38A'}
DAY=[TELEGRAM_L,SLATE_L]; NIGHT=[TELEGRAM_D,GRAPHITE,AMOLED,SLATE_D,SLATE_GRAPHITE,SLATE_AMOLED]

# Contrast fit (check_contrast.py verifies every pair): each accent slot must read on every surface of the mode, in both styles
# and under every night background. Slots that already pass keep their (hand-picked) value.
for mode,T,S,away in (('day',L,DAY,K),('night',D,NIGHT,W)):
    for a,seed in ACC:
        t=T[a]
        bgs=[s['background'] for s in S]; bubbles=[s['bubble_in'] for s in S]
        if mode=='day':
            if cr(W,seed)>=3: t['primary']=fit(t['primary'],W,4.5); t['on_primary']=W  # white button text
            else: t['on_primary']=DARK_TEXT  # light seeds (cyan, orange, gold) keep their hue and take dark text
        elif cr(t['on_primary'],t['primary'])<4.5:
            t['on_primary']=max((W,DARK_TEXT),key=lambda c:cr(c,t['primary']))  # the light night primaries take dark text
        t['primary']=fit_all(t['primary'],bgs,3,away)
        t['primary']=fit(t['primary'],t['on_primary'],4.5,W if t['on_primary']==DARK_TEXT else K)
        if mode=='day':
            t['primary_container']=fit(t['primary_container'],W,4.5)  # the Telegram day drawer header, white text
            # fills under a white glyph (FAB = secondary, badges): keep white readable instead of flipping to a dark glyph
            for r in ('secondary','badge'):
                if t['on_primary']==W: t[r]=fit(t[r],W,3)
        t['bubble_out']=fit_all(t['bubble_out'],[s['text'] for s in S],4.5,W if mode=='day' else K)
        # link = the accent as text: also the Slate dock's selected label (on the top bar) and icon (on its 14 % pill)
        t['link']=fit_all(t['link'],bgs+bubbles+[s['top_bar'] for s in S]+[t['bubble_out']],4.5,away)
        t['link']=fit_tint(t['link'],[s['top_bar'] for s in S],0.14,3,away)
        t['time_out']=fit(t['time_out'],t['bubble_out'],4.5,away)
        for r in ('check_read','accent_out'): t[r]=fit(t[r],t['bubble_out'],3,away)
        t['accent_in']=fit_all(t['accent_in'],bubbles,3,away)
        # a reaction chip not mine: the count in accent_in / accent_out on the accent at 14 % over the bubble
        t['accent_in']=fit_tint(t['accent_in'],bubbles,0.14,3,away)
        t['accent_out']=fit_tint(t['accent_out'],[t['bubble_out']],0.14,3,away)

XML_KEYS=list(NEUTRAL_L)
HEAD='<?xml version="1.0" encoding="utf-8"?>\n<!-- generated: one seed per accent, art/tatrak/gen_accents.py; blue keeps the original Compose colours where they pass contrast -->\n<resources>\n'
# night overlays: equal to their style's day neutrals by day, so BaseActivity can apply them in either mode
OVERLAYS=[('graphite',NEUTRAL_L,GRAPHITE),('amoled',NEUTRAL_L,AMOLED),('slate',SLATE_L,SLATE_D),('slate_graphite',SLATE_L,SLATE_GRAPHITE),('slate_amoled',SLATE_L,SLATE_AMOLED)]
def colors(table,neutral,night):
    out=HEAD
    for k,v in neutral.items(): out+=f'    <color name="tatrak_neutral_{k}">#FF{v}</color>\n'
    for name,d,n in OVERLAYS:
        for k in XML_KEYS: out+=f'    <color name="tatrak_{name}_neutral_{k}">#FF{(n if night else d)[k]}</color>\n'
    for a,_ in ACC:
        for r in ROLES: out+=f'    <color name="tatrak_accent_{a}_{r}">#FF{table[a][r]}</color>\n'
    return out+'</resources>\n'
open(f'{res}/values/tatrak_accent_colors.xml','w').write(colors(L,NEUTRAL_L,False))
open(f'{res}/values-night/tatrak_accent_colors.xml','w').write(colors(D,NEUTRAL_D,True))
M3={'colorPrimary':'primary','colorOnPrimary':'on_primary','colorPrimaryContainer':'primary_container','colorOnPrimaryContainer':'on_primary_container','colorSecondary':'secondary','colorOnSecondary':'on_primary','colorSecondaryContainer':'secondary_container','colorOnSecondaryContainer':'on_secondary_container','colorTertiary':'secondary','colorOnTertiary':'on_primary','colorTertiaryContainer':'secondary_container','colorOnTertiaryContainer':'on_secondary_container'}
TATRAK={'tatrakLink':'link','tatrakBubbleOutgoing':'bubble_out','tatrakTimeOutgoing':'time_out','tatrakCheckRead':'check_read','tatrakUnreadBadge':'badge','tatrakAccentIn':'accent_in','tatrakAccentOut':'accent_out'}
# containers as ColorScheme.withNeutrals in Theme.kt maps them, so XML dialogs and sheets match Compose (Low = the background)
NEU={'android:colorBackground':'background','colorOnBackground':'on_background','colorSurface':'surface','colorOnSurface':'on_surface','colorSurfaceVariant':'surface_variant','colorOnSurfaceVariant':'on_surface_variant','colorOutline':'outline',
     'colorSurfaceContainerLowest':'container_lowest','colorSurfaceContainerLow':'background','colorSurfaceContainer':'container','colorSurfaceContainerHigh':'container','colorSurfaceContainerHighest':'container_highest','colorOutlineVariant':'outline_variant'}
def neutral_style(name,prefix,comment): return f'    <!-- {comment} -->\n    <style name="{name}" parent="">\n'+''.join(f'        <item name="{k}">@color/{prefix}_{v}</item>\n' for k,v in NEU.items())+'    </style>\n'
out='<?xml version="1.0" encoding="utf-8"?>\n<!-- generated with tatrak_accent_colors.xml; applied by BaseActivity unless dynamic colours are on -->\n<resources>\n'
out+='    <style name="ThemeOverlay.Tatrak" parent="">\n'+''.join(f'        <item name="{k}">@color/tatrak_neutral_{v}</item>\n' for k,v in NEU.items())+'    </style>\n'
out+=neutral_style('ThemeOverlay.Tatrak.Graphite','tatrak_graphite_neutral','night background "graphite", Telegram style: applied after ThemeOverlay.Tatrak and before the accent; equals the light neutrals by day')
out+=neutral_style('ThemeOverlay.Tatrak.Amoled','tatrak_amoled_neutral','night background "AMOLED black", Telegram style; equals the light neutrals by day')
out+=neutral_style('ThemeOverlay.Tatrak.SlateSea','tatrak_slate_neutral','Slate and Sea neutrals (blue-grey night): applied after ThemeOverlay.Tatrak and before the accent')
out+=neutral_style('ThemeOverlay.Tatrak.SlateSea.Graphite','tatrak_slate_graphite_neutral','Slate and Sea with the graphite night: applied instead of ThemeOverlay.Tatrak.SlateSea; equals it by day')
out+=neutral_style('ThemeOverlay.Tatrak.SlateSea.Amoled','tatrak_slate_amoled_neutral','Slate and Sea with the AMOLED night: applied instead of ThemeOverlay.Tatrak.SlateSea; equals it by day')
# parent="": without it aapt2 makes the dotted name inherit ThemeOverlay.Tatrak, and every accent would re-apply the default neutrals over the night background
out+='    <style name="ThemeOverlay.Tatrak.Accent" parent="" />\n'
for a,_ in ACC:
    out+=f'    <style name="ThemeOverlay.Tatrak.Accent.{a.capitalize()}">\n'+''.join(f'        <item name="{k}">@color/tatrak_accent_{a}_{v}</item>\n' for k,v in list(M3.items())+list(TATRAK.items()))+'    </style>\n'
out+='</resources>\n'
open(f'{res}/values/tatrak_accent_themes.xml','w').write(out)
attrs='<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <!-- accent colours the chat UI needs beyond Material3, set by ThemeOverlay.Tatrak.Accent.* -->\n'+''.join(f'    <attr name="{k}" format="color" />\n' for k in TATRAK)+'</resources>\n'
open(f'{res}/values/tatrak_attrs.xml','w').write(attrs)
# Compose side: the same neutrals for the Compose-only slots
def col(v): return 'null' if v is None else 'Color(0x%s)'%(v if len(v)==8 else 'FF'+v)
def camel(k): p=k.split('_'); return p[0]+''.join(t.capitalize() for t in p[1:])
KT_KEYS=[k for k in GRAPHITE if k not in ('surface','on_surface','on_background','outline')]+['online']
NULLABLE={'drawer_header','service','divider','text','on_top_bar','online'}
def val(name,n): return f'private val {name}: Neutrals =\n    Neutrals(\n'+''.join(f'        {camel(k)} = {col(n.get(k))},\n' for k in KT_KEYS)+'    )\n'
kt=('package am.cyberhayq.chat.ui.theme\n\nimport androidx.compose.ui.graphics.Color\n\n// generated by art/tatrak/gen_accents.py, do not edit by hand: re-run the generator\n\n'
 '/** Neutral surfaces of the Slate and Sea style or of a night background (instead of Telegram\'s blue night / plain day); null = keep the default. */\n'
 'internal class Neutrals(\n'+''.join(f'    val {camel(k)}: Color{"?" if k in NULLABLE else ""},\n' for k in KT_KEYS)+')\n\n'
 '/**\n * The style\'s surfaces: Slate and Sea by day, the [night] background\'s by night (each style has its own blue-grey and graphite);\n'
 ' * null = the default Telegram look. (Dynamic colours drop all of them in the caller.)\n */\n'
 'internal fun neutralsOf(style: TatrakStyle, dark: Boolean, night: NightBackground): Neutrals? =\n'
 '    when {\n'
 '        !dark -> if (style == TatrakStyle.SLATE_SEA) slateDay else null\n'
 '        style == TatrakStyle.SLATE_SEA ->\n'
 '            when (night) {\n'
 '                NightBackground.BLUE_GREY -> slateNight\n'
 '                NightBackground.GRAPHITE -> slateGraphiteNight\n'
 '                NightBackground.AMOLED -> slateAmoledNight\n'
 '            }\n'
 '        else ->\n'
 '            when (night) {\n'
 '                NightBackground.BLUE_GREY -> null\n'
 '                NightBackground.GRAPHITE -> graphiteNight\n'
 '                NightBackground.AMOLED -> amoledNight\n'
 '            }\n'
 '    }\n\n'
 +'\n'.join(val(n,d) for n,d in (('graphiteNight',GRAPHITE),('amoledNight',AMOLED),('slateNight',SLATE_D),('slateGraphiteNight',SLATE_GRAPHITE),('slateAmoledNight',SLATE_AMOLED),('slateDay',SLATE_L))))
open(os.path.join(res,'..','kotlin/am/cyberhayq/chat/ui/theme/Neutrals.kt'),'w').write(kt)
