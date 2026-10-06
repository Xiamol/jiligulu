"""Original short Foley / pluck feedback, no sampled commercial assets.

44.1 kHz mono PCM, DC removed, 2 ms edge fades and peak below full scale.
UI clicks are muffled; envelopes use granular filtered noise, not a wood knock.
"""
from pathlib import Path
import math, random, struct, wave, json

RATE = 44100
DEST = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw'
TAU = 2 * math.pi

def noise(duration, seed, cutoff=2400):
    rng = random.Random(seed)
    coefficient = 1-math.exp(-TAU*cutoff/RATE)
    previous = 0
    result=[]
    for _ in range(round(duration*RATE)):
        previous += coefficient*(rng.uniform(-1,1)-previous)
        result.append(previous)
    return result

def impact(duration, modes, seed, noise_level=.18, cutoff=2600):
    raw = noise(duration,seed,cutoff)
    return [sum(level*math.sin(TAU*frequency*t)*math.exp(-decay*t)
                for frequency,level,decay in modes)+noise_level*raw[i]*math.exp(-95*t)
            for i in range(len(raw)) for t in [i/RATE]]

def phrase(notes, duration):
    out=[0.0]*round(duration*RATE)
    for start,frequency,level in notes:
        for i in range(round(start*RATE),len(out)):
            t=i/RATE-start
            if t>.27: break
            out[i]+=level*(math.sin(TAU*frequency*t)+.22*math.sin(TAU*frequency*2.01*t))*math.exp(-19*t)*min(1,t/.004)
    return out

def paper():
    high=noise(.19,72,5800)
    low=noise(.19,72,380)
    return [(high[i]-low[i])*sum(math.exp(-((t-center)/width)**2)*level
        for center,width,level in [(.031,.014,.8),(.079,.024,.65),(.139,.017,.42)])
        for i in range(len(high)) for t in [i/RATE]]

def swish():
    raw=noise(.085,20,1500)
    return [raw[i]*math.sin(math.pi*i/len(raw))**2 for i in range(len(raw))]

def double(first,second,offset,duration):
    out=[0.0]*round(duration*RATE)
    for start,values in [(0,first),(round(offset*RATE),second)]:
        for i,x in enumerate(values):
            if start+i<len(out): out[start+i]+=x
    return out

specs={
 'ui_tap': impact(.034,[(220,.4,150),(620,.1,190)],1,.22,1100),
 'ui_select': impact(.027,[(510,.2,150),(890,.1,185)],2,.2,1500),
 'ui_navigate': swish(),
 'ui_toggle': double(impact(.02,[(670,.4,170)],3),impact(.023,[(480,.25,155)],4),.014,.041),
 'ui_paper': paper(),
 'ui_confirm': phrase([(0,523.25,.5),(.065,659.25,.33)],.22),
 'ui_remove': impact(.05,[(170,.5,92),(330,.15,120)],5,.16,1500),
 'ui_calculator': impact(.032,[(180,.4,140),(490,.13,160)],6,.15,950),
 'ui_piece_select': impact(.036,[(510,.45,104),(1370,.17,180)],7,.26,3700),
 'ui_wood_move': impact(.105,[(370,.5,45),(920,.17,60),(1690,.13,100)],8,.24,2800),
 'ui_stone_move': impact(.063,[(1730,.45,90),(2680,.17,115),(3230,.1,150)],9,.22,6400),
 'ui_capture': double(impact(.074,[(340,.5,45),(800,.2,65)],10),impact(.05,[(1450,.27,100)],11),.033,.12),
 'ui_check': double(impact(.056,[(460,.45,63)],12),impact(.056,[(580,.37,72)],13),.106,.19),
 'ui_win': phrase([(0,392,.5),(.12,493.88,.42),(.24,587.33,.36),(.37,783.99,.32)],.66),
 'ui_lose': phrase([(0,392,.38),(.14,329.63,.32),(.27,293.66,.26)],.55),
 'ui_draw': phrase([(0,392,.38),(.12,523.25,.3),(.24,392,.24)],.49),
 'ui_wheel_tick': impact(.022,[(740,.35,170),(1760,.12,215)],14,.34,4800),
 'ui_lamp': double(impact(.022,[(880,.38,150)],15),impact(.022,[(380,.18,130)],16),.019,.045),
 'ui_pet': impact(.095,[(240,.45,40),(490,.13,53)],17,.08,850),
}
specs['ui_drop']=specs['ui_stone_move']  # compatibility only; new callers choose material
report=[]
for name,raw in specs.items():
    dc=sum(raw)/len(raw)
    values=[x-dc for x in raw]
    peak=max(abs(x) for x in values) or 1
    values=[x/peak*.58*min(1,i/(RATE*.002),(len(values)-1-i)/(RATE*.002)) for i,x in enumerate(values)]
    pcm=[round(x*32767) for x in values]
    assert max(abs(x) for x in pcm)<32767 and pcm[0]==pcm[-1]==0
    with wave.open(str(DEST/(name+'.wav')),'wb') as output:
        output.setparams((1,2,RATE,0,'NONE','not compressed'))
        output.writeframes(struct.pack('<'+'h'*len(pcm),*pcm))
    report.append({'asset':name,'milliseconds':round(len(pcm)*1000/RATE),
        'peak':round(max(abs(x) for x in values),3),'rms':round(math.sqrt(sum(x*x for x in values)/len(values)),3)})
print(json.dumps(report,indent=2))
