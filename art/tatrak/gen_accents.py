# Usage: python3 art/tatrak/gen_accents.py src/main/res
# Generates res/values{,-night}/tatrak_accent_colors.xml, res/values/tatrak_accent_themes.xml and
# values-night/tatrak_accent_themes.xml (Ember night) and
# kotlin/am/cyberhayq/chat/ui/theme/HouseNeutrals.kt (neutral surfaces of the houses and of the Slate and Sea style)
import sys
res=sys.argv[1]
def h(c): return tuple(int(c[i:i+2],16) for i in (0,2,4))
def x(t): return '%02X%02X%02X'%tuple(max(0,min(255,round(v))) for v in t)
def mix(a,b,t): a,b=h(a),h(b); return x(tuple(a[i]+(b[i]-a[i])*t for i in range(3)))
W,K,NIGHT_BG,NIGHT_DEEP='FFFFFF','000000','1D2733','0E1621'
ROLES=['primary','on_primary','primary_container','on_primary_container','secondary','secondary_container','on_secondary_container','link','bubble_out','time_out','check_read','badge','accent_in','accent_out']
def light(s): return dict(primary=s,on_primary=W,primary_container=mix(s,K,0.3),on_primary_container=W,secondary=mix(s,W,0.25),secondary_container=mix(s,W,0.8),on_secondary_container=mix(s,K,0.6),link=mix(s,K,0.1),bubble_out=mix(s,W,0.86),time_out=mix(s,K,0.1),check_read=s,badge=s,accent_in=s,accent_out=mix(s,K,0.05))
def dark(s): p=mix(s,W,0.25); return dict(primary=p,on_primary=W,primary_container='212D3B',on_primary_container=W,secondary=p,secondary_container=mix(s,NIGHT_BG,0.6),on_secondary_container=W,link=mix(s,W,0.4),bubble_out=mix(s,NIGHT_DEEP,0.55),time_out=mix(s,W,0.45),check_read=mix(s,W,0.3),badge=mix(s,NIGHT_DEEP,0.35),accent_in=mix(s,W,0.3),accent_out=mix(s,W,0.3))
ACC=[('blue','298ACF'),('cyan','1EA6B6'),('sea','0B6F7A'),('green','3FA34D'),('violet','8E63CE'),('pink','E05A8D'),('orange','EB8A2F'),('red','D9534F'),('ember','B3121B'),('steel','3F86B8'),('crimson','B01E2F'),('gold','D4A017'),('teal','1F7F86'),('forest','4F8A3B'),('copper','D4501A'),('onedark','4078F2'),('dracula','644AC9')]
def lum(c): return sum(w*(v/12.92 if v<=.03928 else ((v+.055)/1.055)**2.4) for w,v in zip((.2126,.7152,.0722),(t/255 for t in h(c))))
def cr(a,b): p,q=sorted((lum(a),lum(b)),reverse=True); return (p+.05)/(q+.05)
def fit(c,bg,ratio,toward=K):  # c nudged toward `toward` until it reaches `ratio` contrast on bg (WCAG AA body text: 4.5)
    t=0
    while cr(mix(c,toward,t),bg)<ratio and t<1: t+=.01
    return mix(c,toward,t)
HOUSE_NAMES=('steel','crimson','gold','teal','forest','copper')
# editor palettes (One Dark / One Light, Dracula / Alucard): night accent N, hand-picked neutrals (below)
PALETTES={'onedark':dict(N='61AFEF'),'dracula':dict(N='BD93F9')}
# house seeds: white text on the day accent must read, except Gold's gold (dark text on it)
ACC=[(a,fit(s,W,6.0 if a in PALETTES else 4.8) if (a in HOUSE_NAMES or a in PALETTES) and a!='gold' else s) for a,s in ACC]
L={a:light(s) for a,s in ACC}; D={a:dark(s) for a,s in ACC}
# blue = the Compose colours the app shipped with
L['blue'].update(primary='298ACF',primary_container='527DA3',secondary='65A9E0',link='2678B6',bubble_out='EFFFDE',time_out='6EB869',check_read='46AA36',badge='229AF0',accent_in='3A8DDA',accent_out='59B159')
D['blue'].update(primary='5DA7E8',secondary='5DA7E8',link='71BAFA',bubble_out='2B5278',time_out='7DA8D3',check_read='64B5EF',badge='3E6A99',accent_in='64B5EF',accent_out='5FA6E6')

# Sea (accent of the Slate and Sea style): teal seed, dark text on the light night primary, hand-picked bubbles
L['sea'].update(bubble_out='CFE9EC',time_out='3A585C')
D['sea'].update(primary='5CC4CE',on_primary='0B1F22',primary_container='12414A',secondary='5CC4CE',link='7FD6DE',bubble_out='12414A',time_out='A9CED3',check_read='7FD6DE',badge='0B6F7A',accent_in='7FD6DE',accent_out='7FD6DE')

# Ember: hand-picked dragon reds and deep black night surfaces (its day keeps the light neutrals)
D['ember'].update(primary='D8323A',primary_container='7A0F15',secondary='E5484D',secondary_container='3A1215',link='FF7A7D',bubble_out='6B1117',time_out='F0A0A2',check_read='FF6B70',badge='C4161E',accent_in='FF6B70',accent_out='FF8A8D')
NEUTRAL_L=dict(background='FFFFFF',on_background='1A1D21',surface='FFFFFF',on_surface='1A1D21',surface_variant='F1F1F1',on_surface_variant='75787A',outline='C4C7CA')
NEUTRAL_D=dict(background='1D2733',on_background='E9EDF0',surface='1D2733',on_surface='E9EDF0',surface_variant='242F3D',on_surface_variant='8D9BA8',outline='3A4756')
# Houses: night accent N, a hue T that washes their neutral surfaces, optional gold-like secondary G (day, night)
# and whether on_primary is dark. Ember has hand-picked neutrals (below), the others derive from T.
HOUSES={
 'steel':    dict(N='8FB4D3',T='5E7F9B'),
 'crimson':dict(N='E8596A',T='9A6A3C',G=('B8941F','E0BC55')),
 'gold':dict(N='E0B83A',T='9A8330'),
 'teal':  dict(N='4DB9BF',T='3E7C82'),
 'forest':   dict(N='7DB866',T='5E8A4A',G=('A8862E','D9BE6E')),
 'copper':  dict(N='F0793C',T='B9783C'),
}
def tint(g,T,k): return mix(x((g,g,g)),T,k)  # grey level g washed with hue T
for a,c in {**HOUSES,**PALETTES}.items():
    N=c['N']
    D[a].update(primary=N,primary_container=mix(N,K,.62),secondary=mix(N,W,.15),secondary_container=mix(N,K,.78),link=mix(N,W,.35),bubble_out=mix(N,K,.66),time_out=mix(N,W,.55),check_read=mix(N,W,.25),badge=mix(N,K,.45),accent_in=mix(N,W,.2),accent_out=mix(N,W,.3))
    if 'G' in c:
        gd,gn=c['G']
        L[a].update(secondary=gd,secondary_container=mix(gd,W,.75),on_secondary_container=mix(gd,K,.6))
        D[a].update(secondary=gn,secondary_container=mix(gd,K,.68),link=gn)
    D[a].update(on_primary=max(('FFFFFF','1A1A1A'),key=lambda t:cr(t,N)))  # light accents take dark text
    if a=='gold': L[a].update(on_primary='1A1A1A')  # white on gold is unreadable
    # day text / icon colours must read on the white-ish surfaces (gold would not)
    for r,q in (('link',4.8),('time_out',4.8),('badge',4.8),('check_read',3),('accent_in',3),('accent_out',3)): L[a][r]=fit(L[a][r],L[a]['bubble_out'] if r in ('link','time_out') else W,q)
# Neutral surfaces per mode: XML attrs (the overlay) plus the Compose-only slots (ChatColors / ColorScheme).
# The first byte of an 8-digit `service` is its opacity (date chips); None = keep the default.
def night_neutrals(T,N):
    sv=tint(0x18,T,.11); chat=tint(0x07,T,.05); bub=tint(0x1B,T,.11)
    return dict(background=tint(0x0C,T,.07),on_background=tint(0xEC,T,.06),surface=tint(0x0C,T,.07),on_surface=tint(0xEC,T,.06),surface_variant=sv,
        on_surface_variant=tint(0xA2,T,.10),outline=tint(0x36,T,.14),
        top_bar=tint(0x14,T,.09),on_top_bar_muted=tint(0xA2,T,.10),drawer_header=mix(N,K,.74),archive=tint(0x36,T,.14),
        chat_background=chat,bubble_in=bub,time_in=tint(0x8A,T,.10),online=mix(N,W,.2),service='CC'+tint(0x24,T,.12),divider=bub,
        outline_variant=tint(0x26,T,.12),container_lowest=chat,container=sv,container_highest=tint(0x22,T,.12))
def day_neutrals(T):
    bg=mix(W,T,.05); sv=mix(W,T,.14)
    return dict(background=bg,on_background='1A1D21',surface=bg,on_surface='1A1D21',surface_variant=sv,on_surface_variant=tint(0x64,T,.16),outline=mix('C4C7CA',T,.2),
        top_bar=bg,on_top_bar_muted='848688',drawer_header=None,archive=mix('B8C2CC',T,.2),
        chat_background=mix('DADADA',T,.24),bubble_in=bg,time_in=mix('A1AAB3',T,.2),online='4BCB1C',service='A6'+mix(mix('DADADA',T,.24),K,.5),divider=None,
        outline_variant=mix('D9D9D9',T,.2),container_lowest=bg,container=bg,container_highest=sv)
NEU_N={'ember':dict(background='0B0B0C',on_background='ECE6E6',surface='0B0B0C',on_surface='ECE6E6',surface_variant='181314',on_surface_variant='A09494',outline='3A2A2C',
        top_bar='141011',on_top_bar_muted='A09494',drawer_header='3A1215',archive='3A2A2C',chat_background='070707',bubble_in='1A1516',time_in='8A7E7E',online='FF6B70',
        service='CC241A1C',divider='1A1516',outline_variant='2A1F20',container_lowest='070707',container='181314',container_highest='221A1C')}
def pal_night(bg,top,chat,bub,sv,hi,txt,comment,outline,online,drawer):
    mut=fit(comment,max((sv,hi,bub,top),key=lum),4.5,W)  # muted text on the lightest surface
    return dict(background=bg,on_background=txt,surface=bg,on_surface=txt,surface_variant=sv,on_surface_variant=mut,outline=fit(outline,bg,3,W),
        top_bar=top,on_top_bar=txt,on_top_bar_muted=mut,drawer_header=drawer,archive=outline,chat_background=chat,bubble_in=bub,
        time_in=fit(comment,bub,4.5,W),text=txt,online=online,service='CC'+bub,divider=bub,outline_variant=hi,container_lowest=chat,container=top,container_highest=hi)
def pal_day(a,bg,top,chat,bub,sv,hi,txt,comment,outline,online):
    mut=fit(comment,min((sv,hi,chat,bg),key=lum),4.5,K)  # muted text on the darkest surface
    return dict(background=bg,on_background=txt,surface=bg,on_surface=txt,surface_variant=sv,on_surface_variant=mut,outline=fit(outline,bg,3,K),
        top_bar=top,on_top_bar=txt,on_top_bar_muted=mut,drawer_header=fit(L[a]['primary'],W,4.5),archive=fit('B8C2CC',W,3,K),
        chat_background=chat,bubble_in=bub,time_in=fit(comment,bub,4.5,K),text=txt,online=online,service='B3'+mix(chat,K,.85),divider=bub,
        outline_variant=hi,container_lowest=bub,container=top,container_highest=hi)
NEU_N['onedark']=pal_night('21252B','282C34','1B1F24','2C313C','2C313C','333842','ABB2BF','5C6370','4B5263','98C379','3E4451')
NEU_N['dracula']=pal_night('21222C','282A36','191A21','343746','343746','3E4154','F8F8F2','6272A4','44475A','50FA7B','44475A')
NEU_L={}
NEU_L['onedark']=pal_day('onedark','FAFAFA','FFFFFF','E8E8EA','FFFFFF','EAEAEB','E5E5E6','383A42','A0A1A7','C4C7CA','50A14F')
NEU_L['dracula']=pal_day('dracula','FFFBEB','FFFDF5','EDE9D8','FFFDF5','F1EDDC','E8E3D0','1F1F1F','8A8574','C4C0AE','14710A')
for a,c in HOUSES.items():
    NEU_N[a]=night_neutrals(c['T'],c['N']); NEU_L[a]=day_neutrals(c['T'])
# Slate and Sea style (opt-in, see design_draft.md): cool slate surfaces; ground = background, raised = bars / incoming bubbles / sheets
SLATE_L=dict(background='EEF1F4',on_background='14191F',surface='EEF1F4',on_surface='14191F',surface_variant='E3E9EE',on_surface_variant='56616C',outline='7C8791',
        top_bar='F8FAFB',on_top_bar='14191F',on_top_bar_muted='56616C',drawer_header='2B3A44',archive='7F8C98',chat_background='E3E9EE',bubble_in='F8FAFB',
        time_in='56616C',text='14191F',service='B3'+'26323B',divider='F8FAFB',outline_variant='DCE2E8',container_lowest='F8FAFB',container='F8FAFB',container_highest='DCE2E8',online='2FA85A')
SLATE_D=dict(background='0F1316',on_background='E6EAEE',surface='0F1316',on_surface='E6EAEE',surface_variant='1F262B',on_surface_variant='98A3AD',outline='5E6B76',
        top_bar='171C20',on_top_bar='E6EAEE',on_top_bar_muted='98A3AD',drawer_header='1F262B',archive='3A444D',chat_background='0F1316',bubble_in='1F262B',
        time_in='98A3AD',text='E6EAEE',service='CC1F262B',divider='1F262B',outline_variant='232B31',container_lowest='0F1316',container='171C20',container_highest='2A323A',online='4CC38A')
# Slate and Sea under the elegant switch: graphite with a light cool tint instead of the blue-grey slate night
SLATE_ELEGANT=dict(background='111315',on_background='E8EBED',surface='111315',on_surface='E8EBED',surface_variant='1B1E21',on_surface_variant='A0A6AB',outline='5F666D',
        top_bar='181B1E',on_top_bar='E8EBED',on_top_bar_muted='A0A6AB',drawer_header='1E2124',archive='3A3F44',chat_background='0B0C0E',bubble_in='1E2124',
        time_in='9399A0',text='E8EBED',service='CC1E2124',divider='1E2124',outline_variant='25292D',container_lowest='0B0C0E',container='181B1E',container_highest='2A2E32',online='4CC38A')
# Elegant dark (opt-in night style): calm charcoal layers and off-white text for every accent without own neutrals
ELEGANT=dict(background='121214',on_background='ECE9E6',surface='121214',on_surface='ECE9E6',surface_variant='1C1C1F',on_surface_variant='A7A4A1',outline='3A3A3E',
        top_bar='1A1A1D',on_top_bar='ECE9E6',on_top_bar_muted='A7A4A1',drawer_header='222226',archive='3A3A3E',chat_background='0B0B0D',bubble_in='1E1E22',
        time_in='8E8B88',text='ECE9E6',service='CC1E1E22',divider='1E1E22',outline_variant='2A2A2E',container_lowest='0B0B0D',container='1C1C1F',container_highest='2A2A2E')
XML_KEYS=list(NEUTRAL_L)
HEAD='<?xml version="1.0" encoding="utf-8"?>\n<!-- generated: one seed per accent, art/tatrak/gen_accents.py; blue keeps the original Compose colours -->\n<resources>\n'
def colors(table,neutral,house_neutral,elegant,slate,slate_elegant):
    out=HEAD
    for k,v in neutral.items(): out+=f'    <color name="tatrak_neutral_{k}">#FF{v}</color>\n'
    for k in XML_KEYS: out+=f'    <color name="tatrak_elegant_neutral_{k}">#FF{elegant[k]}</color>\n'
    for k in XML_KEYS: out+=f'    <color name="tatrak_slate_neutral_{k}">#FF{slate[k]}</color>\n'
    for k in XML_KEYS: out+=f'    <color name="tatrak_slate_elegant_neutral_{k}">#FF{slate_elegant[k]}</color>\n'
    for a,_ in ACC:
        for r in ROLES: out+=f'    <color name="tatrak_accent_{a}_{r}">#FF{table[a][r]}</color>\n'
    for a,n in house_neutral.items():
        for k in XML_KEYS: out+=f'    <color name="tatrak_accent_{a}_neutral_{k}">#FF{n[k]}</color>\n'
    return out+'</resources>\n'
# Ember has no day neutrals (its day overlay sets none), so the Slate overlay shows through by day
# night-only house neutrals (Ember) also need a base declaration, or lint (MissingDefaultResource) fails the release build; the day style never references them
open(f'{res}/values/tatrak_accent_colors.xml','w').write(colors(L,NEUTRAL_L,{**{a:n for a,n in NEU_N.items() if a not in NEU_L},**NEU_L},NEUTRAL_L,SLATE_L,SLATE_L))
open(f'{res}/values-night/tatrak_accent_colors.xml','w').write(colors(D,NEUTRAL_D,NEU_N,ELEGANT,SLATE_D,SLATE_ELEGANT))
M3={'colorPrimary':'primary','colorOnPrimary':'on_primary','colorPrimaryContainer':'primary_container','colorOnPrimaryContainer':'on_primary_container','colorSecondary':'secondary','colorOnSecondary':'on_primary','colorSecondaryContainer':'secondary_container','colorOnSecondaryContainer':'on_secondary_container','colorTertiary':'secondary','colorOnTertiary':'on_primary','colorTertiaryContainer':'secondary_container','colorOnTertiaryContainer':'on_secondary_container'}
TATRAK={'tatrakLink':'link','tatrakBubbleOutgoing':'bubble_out','tatrakTimeOutgoing':'time_out','tatrakCheckRead':'check_read','tatrakUnreadBadge':'badge','tatrakAccentIn':'accent_in','tatrakAccentOut':'accent_out'}
NEU={'android:colorBackground':'background','colorOnBackground':'on_background','colorSurface':'surface','colorOnSurface':'on_surface','colorSurfaceVariant':'surface_variant','colorOnSurfaceVariant':'on_surface_variant','colorOutline':'outline'}
out='<?xml version="1.0" encoding="utf-8"?>\n<!-- generated with tatrak_accent_colors.xml; applied by BaseActivity unless dynamic colours are on -->\n<resources>\n'
out+='    <style name="ThemeOverlay.Tatrak" parent="">\n'+''.join(f'        <item name="{k}">@color/tatrak_neutral_{v}</item>\n' for k,v in NEU.items())+'    </style>\n'
out+='    <!-- opt-in elegant night: applied between ThemeOverlay.Tatrak and the accent, so houses still override it; equals the light neutrals by day -->\n    <style name="ThemeOverlay.Tatrak.Elegant" parent="">\n'+''.join(f'        <item name="{k}">@color/tatrak_elegant_neutral_{v}</item>\n' for k,v in NEU.items())+'    </style>\n'
out+='    <!-- Slate and Sea neutrals: applied after Tatrak / Elegant and before the accent, so a house that defines neutrals for the mode still wins -->\n    <style name="ThemeOverlay.Tatrak.SlateSea" parent="">\n'+''.join(f'        <item name="{k}">@color/tatrak_slate_neutral_{v}</item>\n' for k,v in NEU.items())+'    </style>\n'
out+='    <!-- Slate and Sea with the elegant switch: applied instead of ThemeOverlay.Tatrak.SlateSea; equals it by day -->\n    <style name="ThemeOverlay.Tatrak.SlateSea.Elegant" parent="">\n'+''.join(f'        <item name="{k}">@color/tatrak_slate_elegant_neutral_{v}</item>\n' for k,v in NEU.items())+'    </style>\n'
# parent="": without it aapt2 makes the dotted name inherit ThemeOverlay.Tatrak, and every accent would re-apply the default neutrals over Slate and Sea / Elegant
out+='    <style name="ThemeOverlay.Tatrak.Accent" parent="" />\n'
def accent_style(a,neutrals):
    o=f'    <style name="ThemeOverlay.Tatrak.Accent.{a.capitalize()}">\n'
    for k,v in list(M3.items())+list(TATRAK.items()): o+=f'        <item name="{k}">@color/tatrak_accent_{a}_{v}</item>\n'
    if neutrals:
        for k,v in NEU.items(): o+=f'        <item name="{k}">@color/tatrak_accent_{a}_neutral_{v}</item>\n'
    return o+'    </style>\n'
for a,_ in ACC: out+=accent_style(a,a in NEU_L)
out+='</resources>\n'
open(f'{res}/values/tatrak_accent_themes.xml','w').write(out)
# Ember only has night neutrals: its night style (this file replaces the day style of the same name) adds them
open(f'{res}/values-night/tatrak_accent_themes.xml','w').write('<?xml version="1.0" encoding="utf-8"?>\n<!-- generated with tatrak_accent_colors.xml; Ember night: the day style has no neutrals -->\n<resources>\n'+accent_style('ember',True)+'</resources>\n')
attrs='<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <!-- accent colours the chat UI needs beyond Material3, set by ThemeOverlay.Tatrak.Accent.* -->\n'+''.join(f'    <attr name="{k}" format="color" />\n' for k in TATRAK)+'</resources>\n'
open(f'{res}/values/tatrak_attrs.xml','w').write(attrs)
# Compose side: the same neutrals for the Compose-only slots
def col(v): return 'null' if v is None else 'Color(0x%s)'%(v if len(v)==8 else 'FF'+v)
def camel(k): p=k.split('_'); return p[0]+''.join(t.capitalize() for t in p[1:])
KT_KEYS=[k for k in ELEGANT if k not in ('surface','on_surface','on_background','outline')]+['online']
NULLABLE={'drawer_header','service','divider','text','on_top_bar','online'}
def entry(a,n): return f'        Accent.{a.upper()} to\n            Neutrals(\n'+''.join(f'                {camel(k)} = {col(n.get(k))},\n' for k in KT_KEYS)+'            ),\n'
kt=('package am.cyberhayq.chat.ui.theme\n\nimport androidx.compose.ui.graphics.Color\n\n// generated by art/tatrak/gen_accents.py, do not edit by hand: re-run the generator\n\n'
 '/** Neutral surfaces of a signature theme or of the Slate and Sea style (instead of Telegram\'s blue night / plain day); null = keep the default. */\n'
 'internal class Neutrals(\n'+''.join(f'    val {camel(k)}: Color{"?" if k in NULLABLE else ""},\n' for k in KT_KEYS)+')\n\n'
 '/**\n * Surfaces by precedence: a house that defines neutrals for the mode, else Slate and Sea (its graphite night when\n * [elegant] is on), else the elegant night ones when [elegant] is on; null = the default Telegram look. (Dynamic colours drop all of them in the caller.)\n */\ninternal fun neutralsOf(style: TatrakStyle, accent: Accent, dark: Boolean, elegant: Boolean): Neutrals? =\n    (if (dark) night[accent] else day[accent])\n        ?: if (style == TatrakStyle.SLATE_SEA) (if (!dark) slateDay else if (elegant) slateElegantNight else slateNight) else elegantNight.takeIf { dark && elegant }\n\n'
 'private val night: Map<Accent, Neutrals> =\n    mapOf(\n'+''.join(entry(a,n) for a,n in NEU_N.items())+'    )\n\n'
 'private val elegantNight: Neutrals =\n'+entry('x',ELEGANT).replace('        Accent.X to\n            ','    ').replace('            ),\n','    )\n')+'\nprivate val slateNight: Neutrals =\n'+entry('x',SLATE_D).replace('        Accent.X to\n            ','    ').replace('            ),\n','    )\n')+'\nprivate val slateElegantNight: Neutrals =\n'+entry('x',SLATE_ELEGANT).replace('        Accent.X to\n            ','    ').replace('            ),\n','    )\n')+'\nprivate val slateDay: Neutrals =\n'+entry('x',SLATE_L).replace('        Accent.X to\n            ','    ').replace('            ),\n','    )\n')+'\nprivate val day: Map<Accent, Neutrals> =\n    mapOf(\n'+''.join(entry(a,n) for a,n in NEU_L.items())+'    )\n')
import os
open(os.path.join(res,'..','kotlin/am/cyberhayq/chat/ui/theme/HouseNeutrals.kt'),'w').write(kt)
