# Usage: python3 art/tatrak/check_contrast.py [-v]   (exit 1 if a pair fails; -v prints every pair)
# WCAG contrast of every accent on every surface it is drawn on: 9 accents x {Telegram, Slate and Sea, Glass, Warm Glass} x {day,
# night blue-grey, night graphite, night AMOLED}, plus Tinted Glass (one surface set per accent, checked with that accent only).
# 4.5 for text, 3 for icons, badges and UI parts. Floating bars: their colour at barAlpha() over pure black and pure white
# (blur off), over the style's own content (background, chat background, incoming bubble, the accent's outgoing bubble and
# primary) for "Blur under bars: Frosted"; "Clear" (fixed 0.72) is only reported, its pairs below the need are not failures.
# Reuses cr() / mix() and the colour tables of gen_accents.py; importing it writes the generated files into a temp dir only.
import os,sys,tempfile
verbose='-v' in sys.argv
tmp=tempfile.mkdtemp()
for d in ('res/values','res/values-night','kotlin/am/cyberhayq/chat/ui/theme'): os.makedirs(os.path.join(tmp,d))
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
sys.argv=[sys.argv[0],os.path.join(tmp,'res')]
import gen_accents as g
W='FFFFFF'
def fab_fill(primary): return g.fit(primary,W,4.5)  # fabFill() in Theme.kt: primary darkened until white reaches 4.5:1
CLEAR=0.72  # ClearBarAlpha in FloatingBars.kt
def bar_alpha(bar,fgs,parts=(),mode='off',under=('000000',W)):  # barAlpha() in FloatingBars.kt: off from 0.96, frosted from 0.72, up until every fg reaches 4.5 (the non-text parts 3) on the bar over every colour in under; clear = CLEAR
    if mode=='clear': return CLEAR
    step=72 if mode=='frosted' else 96
    while step<100 and any(g.cr(fg,g.mix(u,bar,step/100))<4.5 or any(g.cr(p,g.mix(u,bar,step/100))<3 for p in parts) for u in under for fg in fgs): step+=1
    return step/100
def over(fg8,bg):  # an 8-digit AARRGGBB colour over an opaque background
    a=int(fg8[:2],16)/255; return g.mix(bg,fg8[2:],a)
# Warning bubble of ChatScreenColors (ChatPalette.kt), the same in both styles (day text darkened from B26A00, which was 3.77)
WARN={'day':('FFF1C9','8F5600'),'night':('4A3D12','FFC94D')}
MODES={('telegram','day'):g.TELEGRAM_L,('telegram','blue-grey'):g.TELEGRAM_D,('telegram','graphite'):g.GRAPHITE,('telegram','amoled'):g.AMOLED,
       ('slate','day'):g.SLATE_L,('slate','blue-grey'):g.SLATE_D,('slate','graphite'):g.SLATE_GRAPHITE,('slate','amoled'):g.SLATE_AMOLED,
       ('glass','day'):g.GLASS_L,('glass','blue-grey'):g.GLASS_D,('glass','graphite'):g.GLASS_GRAPHITE,('glass','amoled'):g.GLASS_AMOLED,
       ('warm','day'):g.WARM_L,('warm','blue-grey'):g.WARM_D,('warm','graphite'):g.WARM_GRAPHITE,('warm','amoled'):g.WARM_AMOLED}
for a,_ in g.ACC:  # Tinted Glass: style 'tinted-<accent>'
    for i,mode in enumerate(('day','blue-grey','graphite','amoled')): MODES[('tinted-'+a,mode)]=g.TINTED[a][i]
TEXT_SURFACES=('background','top_bar','bubble_in','chat_background','container','container_highest','surface_variant')
MUTED_SURFACES=('background','top_bar','bubble_in','container','container_highest','surface_variant')  # muted text is never drawn on the bare wallpaper
fails=0; total=0; low={}; alphas={}; pill_alphas={}; blur_alphas={}; blur_pill_alphas={}; clear_low={}; clear_total=0  # alphas[(style,mode)] = floating bar alphas over the accents (pill_alphas: the chat's composer pill)  # low[(style,mode,label)] = (lowest ratio, accent)
def check(key,label,fg,bg,need,who='',report_only=False):
    global fails,total,clear_total
    r=g.cr(fg,bg); ok=r>=need
    if report_only:  # clear bars: counted and summed up per surface set, never a failure
        clear_total+=1
        if not ok:
            n,worst=clear_low.get(key,(0,None)); clear_low[key]=(n+1,min(worst or (99,),(r,need,label,who)))
        return
    fails+=not ok; total+=1
    k=key+(label,)
    if k not in low or r<low[k][0]: low[k]=(r,who,need)
    if verbose or not ok: print(f"{'ok  ' if ok else 'FAIL'} {r:5.2f} (>= {need}) {' / '.join(key)} {who} {label}")
info=[]
for (style,mode),S in MODES.items():
    key=(style,mode); day=mode=='day'
    for s in TEXT_SURFACES: check(key,'text on surfaces',S['text'],S[s],4.5,s)
    for s in MUTED_SURFACES: check(key,'muted text on surfaces',S['on_surface_variant'],S[s],4.5,s)
    check(key,'top bar muted on top bar',S['on_top_bar_muted'],S['top_bar'],4.5)
    check(key,'time_in on incoming bubble',S['time_in'],S['bubble_in'],4.5)
    check(key,'warning text on warning bubble',WARN['day' if day else 'night'][1],WARN['day' if day else 'night'][0],4.5)
    if 'outline' in S:  # the generated sets (the Telegram defaults keep Material's outline)
        check(key,'outline on background',S['outline'],S['background'],3)
        check(key,'date chip text (white) on chip over the chat background',W,over(S['service'],S['chat_background']),4.5)
        check(key,'drawer header text (white)',W,S['drawer_header'],4.5)
        check(key,'white icon on archive',W,S['archive'],3)
    for a,_ in g.ACC:
        if style.startswith('tinted-') and style!='tinted-'+a: continue  # a tinted set exists for its own accent only
        A=(g.L if day else g.D)[a]
        check(key,'text on outgoing bubble',S['text'],A['bubble_out'],4.5,a)
        check(key,'time_out on outgoing bubble',A['time_out'],A['bubble_out'],4.5,a)
        for s,bg in (('background',S['background']),('bubble_in',S['bubble_in']),('bubble_out',A['bubble_out'])): check(key,f'link on {s}',A['link'],bg,4.5,a)
        check(key,'button text (on_primary) on primary',A['on_primary'],A['primary'],4.5,a)
        check(key,'primary on background',A['primary'],S['background'],3,a)
        check(key,'badge text (contentOn) on badge',g.glyph(A['badge']),A['badge'],3,a)
        check(key,'glyph (contentOn) on primary',g.glyph(A['primary']),A['primary'],3,a)
        # owner's choice (2026-11-15): white pencil on a FAB filled with the primary darkened to 4.5:1 (fabFill() in Theme.kt)
        check(key,'FAB white pencil on fabFill(primary)',W,fab_fill(A['primary']),4.5,a)
        check(key,'check_read on outgoing bubble',A['check_read'],A['bubble_out'],3,a)
        check(key,'accent_out on outgoing bubble',A['accent_out'],A['bubble_out'],3,a)
        check(key,'accent_in on incoming bubble',A['accent_in'],S['bubble_in'],3,a)
        check(key,'round file button glyph (contentOn) on accent_in',g.glyph(A['accent_in']),A['accent_in'],3,a)
        if style=='telegram' and day: check(key,'drawer header text (white) on primary_container',W,A['primary_container'],4.5,a)
        # reaction chips (MessageBubble Reactions): mine = contentOn count on the accent fill (3, as badge counts);
        # not mine = the accent count on the accent at 14 % over the bubble
        for side,acc,bub in (('incoming','accent_in',S['bubble_in']),('outgoing','accent_out',A['bubble_out'])):
            check(key,f'reaction count, mine, on {side} chip',g.glyph(A[acc]),A[acc],3,a)
            check(key,f'reaction count, not mine, on {side} chip',A[acc],g.mix(bub,A[acc],0.14),3,a)
        if style=='slate':  # the dock (ChatListDock): selected = link, on the top bar (label) and on its 14 % pill (icon)
            check(key,'dock label (selected, link) on dock',A['link'],S['top_bar'],4.5,a)
            check(key,'dock icon (selected, link) on the selected pill',A['link'],g.mix(S['top_bar'],A['link'],0.14),3,a)
        # floating bars (FloatingBars.kt): the bar colour at barAlpha() laid over the worst content under it, pure black and white;
        # text = title, muted = folder tabs and tab labels, link = the selected tab (label, and icon on its 14 % pill), primary = folder tab
        on_bar=S.get('on_top_bar','1A1D21' if day else W)
        content=(S['background'],S['chat_background'],S['bubble_in'],A['bubble_out'],A['primary'])  # frostedContent() in FloatingBars.kt
        for mode in ('off','frosted','clear'):
            tag='' if mode=='off' else f' ({mode})'; info_only=mode=='clear'
            under=(('000000','black'),(W,'white')) if mode=='off' else tuple((c,c) for c in content)
            us=tuple(u for u,_ in under)
            fa=bar_alpha(S['top_bar'],(on_bar,S['on_top_bar_muted'],A['link']),(A['primary'],),mode,us)
            if mode!='clear': (blur_alphas if mode=='frosted' else alphas).setdefault(key,set()).add(fa)
            for u,name in under:
                fb=g.mix(u,S['top_bar'],fa)
                check(key,f'floating bar text over {name}{tag}',on_bar,fb,4.5,a,info_only)
                check(key,f'floating bar muted text over {name}{tag}',S['on_top_bar_muted'],fb,4.5,a,info_only)
                check(key,f'floating tab label (selected, link) over {name}{tag}',A['link'],fb,4.5,a,info_only)
                check(key,f'floating tab icon (selected, link) on its pill over {name}{tag}',A['link'],g.mix(fb,A['link'],0.14),3,a,info_only)
                check(key,f'floating folder tab (selected, primary) over {name}{tag}',A['primary'],fb,3,a,info_only)
            # floating chat (Composer.kt floatingPill()): the composer pills and the banner pill are surfaceContainer at barAlpha() for
            # their text, hint / icons (muted) and link (PGP notice button); primary = send icon, cursor, reply title and icon, banner
            # button (3, as on the opaque surfaces); the recording dot (3) and primary also raise the alpha
            pa=bar_alpha(S['container'],(S['text'],S['on_surface_variant'],A['link']),('E53935',A['primary']),mode,us)
            if mode!='clear': (blur_pill_alphas if mode=='frosted' else pill_alphas).setdefault(key,set()).add(pa)
            for u,name in under:
                fp=g.mix(u,S['container'],pa)
                check(key,f'floating composer text over {name}{tag}',S['text'],fp,4.5,a,info_only)
                check(key,f'floating composer hint and icons (muted) over {name}{tag}',S['on_surface_variant'],fp,4.5,a,info_only)
                check(key,f'floating composer link (PGP notice) over {name}{tag}',A['link'],fp,4.5,a,info_only)
                check(key,f'floating composer primary (send, reply title, banner button) over {name}{tag}',A['primary'],fp,3,a,info_only)
                check(key,f'floating composer recording dot (E53935) over {name}{tag}','E53935',fp,3,a,info_only)
        # informational: primary as text (text buttons, section titles) wants 4.5, the check above asks 3
        r=min(g.cr(A['primary'],S[s]) for s in ('background','container','top_bar'))
        if r<4.5: info.append(f'{style}/{mode} {a}: primary as text {r:.2f}')
    if style=='slate':  # phase 2 (design_phase2.md): dock, floating search, composer pill, recording (neutral parts)
        pill=S['container']
        check(key,'dock label (not selected) on dock',S['on_top_bar_muted'],S['top_bar'],4.5)
        check(key,'composer text and placeholder on the pill',S['on_surface_variant'],pill,4.5)
        check(key,'recording dot (E53935) on the pill','E53935',pill,3)
# summary: lowest ratio per pair kind and surface set
print(f'{"style / mode":22} {"lowest ratio (need) pair":60} accent / surface')
for (style,mode,label),(r,who,need) in sorted(low.items(),key=lambda kv:(kv[0][0],list(MODES).index(kv[0][:2]),kv[0][2])):
    print(f'{style+" / "+mode:22} {r:5.2f} ({need}) {label:47} {who}')
print('\nfloating bar alpha (barAlpha) per surface set, over the accents:')
for k,v in alphas.items():
    if v!={0.96}: print(f'  {" / ".join(k)}: {", ".join(f"{x:.2f}" for x in sorted(v))}')
print('  every other set: 0.96')
print('\nfloating composer pill alpha (barAlpha of surfaceContainer) per surface set, over the accents:')
for k,v in pill_alphas.items():
    if v!={0.96}: print(f'  {" / ".join(k)}: {", ".join(f"{x:.2f}" for x in sorted(v))}')
print('  every other set: 0.96')
def ranges(title,table):  # blur: one line per surface set, the lowest and highest alpha over the accents
    print(title)
    for k,v in table.items(): print(f'  {" / ".join(k)}: {min(v):.2f}' + (f'-{max(v):.2f}' if len(v)>1 else ''))
ranges('\nfloating bar alpha, blur Frosted (barAlpha from 0.72 over the content set) per surface set, over the accents:',blur_alphas)
ranges('\nfloating composer pill alpha, blur Frosted, per surface set, over the accents:',blur_pill_alphas)
print(f'\ninformational, blur Clear (every pill at {CLEAR}, over the content set): pairs below the need, per surface set (of {clear_total}):')
for k,(n,(r,need,label,who)) in clear_low.items(): print(f'  {" / ".join(k)}: {n}, worst {r:.2f} (< {need}) {who} {label}')
if not clear_low: print('  none')
print('\ninformational, primary as text below 4.5 (not a failure):'); print('\n'.join('  '+i for i in info) or '  none')
print(f'\n{total} pairs, ', end=''); print(f'{fails} FAILED' if fails else 'all pairs pass'); sys.exit(1 if fails else 0)
