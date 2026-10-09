"""Offline migration using an extracted DEX index; this is not a DexKit resolver.

Input: mapping JSON (v1 migration source or v2 profile) plus DEX/resource evidence. The APK runtime accepts v2 only.
Usage: python tools/generate_profile_v2.py --profile FILE --dex-index INDEX --resources DUMP --output FILE
"""
import argparse
import json
import re
from pathlib import Path


def generate(source, index, resources):
    root = json.loads(source.read_text(encoding="utf-8"))
    if root["schemaVersion"] == 2:
        n = {key: value for feature in root["features"] for key, value in feature["mappings"].items()}
        n.update(profileId=root["profileId"], **root["host"])
    else:
        n = root["names"]
    kotlin = Path("app/src/main/kotlin/com/hmodule/adaptation/FeatureProfiles.kt").read_text(encoding="utf-8")
    groups = {k: v.split() for k, v in re.findall(r'"([a-z_]+)" to "([A-Za-z0-9_ ]+)"', kotlin)}
    arrays = set(re.search(r'private val arrays = setOf\((.*?)\)\n', kotlin, re.S).group(1).replace('\n', ' ').replace(' ', '').replace('"', '').split(','))
    booleans = {"useLegacySeedIds", "structuralFullscreenWatch", "delayedPauseRestore", "nativeDrawer"}
    n["speedPlayerClass"] = n["resolutionController"] if n.get("speedControllerClass") else ""
    mappings = {key: n.get(key, [] if key in arrays else False if key in booleans else {} if key == "controlTargets" else "") for group in groups.values() for key in group}
    # Keep the index small in memory: only declarations, no strings or reference lists.
    classes = {}; current = None
    if index:
        for line in index.open(encoding="utf-8"):
            if line.startswith("CLASS "):
                parts = line.split(); current = {"super": parts[3], "fields": {}, "methods": []}; classes[parts[1]] = current
            elif current is not None and line.startswith(" F "):
                name, type_ = line.strip()[2:].split(":", 1); current["fields"][name] = type_
            elif current is not None and line.startswith(" M "):
                current["methods"].append(line.strip()[2:].replace(",", ""))
    resource_names = set()
    if resources:
        for line in resources.open(encoding="utf-8"):
            resource_names.update(re.findall(r'com\.phoenix\.read:id/([A-Za-z0-9_]+):', line))
    def obj(c): return "L" + c.replace(".", "/") + ";"
    def chain(c):
        seen = set(); c = obj(c)
        while c in classes and c not in seen:
            seen.add(c); yield classes[c]; c = classes[c]["super"]
    features = []
    for id_, keys in groups.items():
        delegates = []
        def add(key, kind, desc, found):
            delegates.append(dict(key=key, kind=kind, descriptor=desc, status="SUCCESS" if found else "UNVERIFIED"))
        def cls(key, owner):
            if owner: add(key, "class", obj(owner), obj(owner) in classes)
        def method(key, owner, name, params=(), result="V"):
            if not owner or not name: return
            owners = [classes[obj(owner)]] if name == "<init>" and obj(owner) in classes else chain(owner)
            matches = [m for c in owners for m in c["methods"] if m.startswith(name + "(") and
                       (params is None or m.split(")")[0] == name + "(" + "".join(params)) and
                       (result is None or m.split(")")[1] == result)]
            matches = list(dict.fromkeys(matches))
            # A constructor must be unique when its parameter contract is unknown.
            signature = matches[0] if len(matches) == 1 else name + "(" + "".join(params or ()) + ")" + (result or "V")
            add(key, "method", obj(owner) + "->" + signature, len(matches) == 1)
        def field(key, owner, name, type_=None):
            if not owner or not name: return
            types = [c["fields"][name] for c in chain(owner) if name in c["fields"]]
            actual = types[0] if types else type_ or "Ljava/lang/Object;"
            add(key, "field", obj(owner) + "->" + name + ":" + actual, bool(types) and (type_ is None or actual == type_))
        def resource(name):
            if name and not any(d["key"] == "resource." + name for d in delegates):
                add("resource." + name, "resource", "id/" + name, name in resource_names)
        if id_ == "playback":
            holder=n["shortHolder"]; base=n["holderBaseS1"]
            cls("shortHolder", holder); cls("holderBaseS1", base)
            method("shortStateMethod", base, n["shortStateMethod"], None)
            for key, params in [("shortControlsMethod",("Z","Z")),("shortMaskMethod",("Z",)),("shortConfigMethod",("Landroid/content/res/Configuration;",)),("shortLayoutResetMethod",()),("shortLandscapeMethod",("Z",))]: method(key, holder, n[key], params)
            field("shortMaskField", holder, n["shortMaskField"], "Landroid/view/View;")
            field("shortNativeClearField", holder, n["shortNativeClearField"], "Z")
            field("shortCleanManagerField", holder, n["shortCleanManagerField"]); cls("playbackState", n["playbackState"])
        elif id_ == "playback_layout":
            home="com.dragon.read.component.shortvideo.impl.v2.SeriesBookMallTabFragment"; series="com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
            method("homeFragmentMaskMethod",home,n["homeFragmentMaskMethod"],("Z",)); field("homeFragmentMaskField",home,n["homeFragmentMaskField"],"Landroid/view/View;")
            method("seriesFragmentRefreshMethod",series,n["seriesFragmentRefreshMethod"],("Z","Z")); method("seriesPagerGetter",series,n["seriesPagerGetter"],result=None)
            method("seriesHolderGetter",n.get("speedControllerClass", ""),n["seriesHolderGetter"],result=None)
            for f in n["seriesLayoutFields"]: field("seriesLayout."+f,series,f)
            method("fixedToolbarShowMethod","com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed",n["fixedToolbarShowMethod"],("Z",))
            owner="com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer"
            method("customizeToolbarShowMethod",owner,n["customizeToolbarShowMethod"],("Z",)); method("customizeToolbarApplyMethod",owner,n["customizeToolbarApplyMethod"],("Z","Z","Z")); cls("toolbarBase",n["toolbarBase"])
        elif id_ == "controls":
            for key in ["progressBar","hideView1","hideView2"]: cls(key,n[key])
            cls("authorDeclaration","com.dragon.read.component.shortvideo.impl.infobottom.ShortSeriesInfoBottomView")
            names = list(n.get("controlTargets",{})) + [name for key in ["clearHostNames","hideIdNames","progressIdNames","mainNavNames","fullSeriesNames","pauseRestoreNames"] for name in n.get(key,[])] + [n.get("bottomBackdropName", "")]
            for name in dict.fromkeys(names): resource(name)
        elif id_ == "quality":
            owner=n["resolutionController"]; cls("resolutionController",owner); field("resolutionEngineField",owner,n["resolutionEngineField"],"Lcom/ss/ttvideoengine/TTVideoEngine;")
            method("resolutionApplyMethod",owner,n.get("resolutionApplyMethod", ""),("Lcom/ss/ttvideoengine/Resolution;",))
            for m in n["resolutionModelMethods"]: method("resolutionModel."+m,owner,m,None)
        elif id_ == "speed" and n.get("speedControllerClass"):
            owner=n["speedControllerClass"]; method("setPlaySpeed",n["speedPlayerClass"],"setPlaySpeed",("I",))
            method("speedSetMethod",owner,n["speedSetMethod"],("Z","F","Z")); method("speedCacheMethod",owner,n["speedCacheMethod"],("Ljava/lang/String;",),"F")
            method("getCurrentPlaySpeed",owner,"getCurrentPlaySpeed",result="I"); method("autoPlaySpeed","com.dragon.read.component.shortvideo.impl.autoplay.o","setSpeed",("F",))
        elif id_ == "comments":
            owner=n["rightViewAgency"]; cls("rightViewAgency",owner); field("rightView",owner,"p","Lcom/dragon/read/component/shortvideo/impl/rightview/ShortSeriesRightView;")
            method("rightViewAgencyEventMethod",owner,n["rightViewAgencyEventMethod"],("Landroid/os/Bundle;","Ljava/lang/String;"))
            for i,owner in enumerate(n["doubleTapHandlers"]): cls("handler."+str(i),owner)
            for i,owner in enumerate(c for c in n["doubleTapHandlers"] if c!=n.get("doubleTapLikeView")): method("onDoubleTap."+str(i),owner,"onDoubleTap",("Landroid/view/MotionEvent;",),"Z")
            method("doubleTapLikeMethod",n.get("doubleTapLikeView", ""),n.get("doubleTapLikeMethod", "a"),("Lkotlin/Pair;",)); method("doubleTapHolderLikeMethod",n["shortHolder"],n.get("doubleTapHolderLikeMethod", ""))
        elif id_ == "settings" and n.get("settingsItemClass"):
            for m in n["settingsListMethods"]: method("settingsList."+m,"com.dragon.read.component.biz.impl.mine.settings.SettingsActivity",m,("Lcom/dragon/read/recyler/c;",),"Ljava/util/List;")
            method("itemConstructor",n["settingsItemClass"],"<init>"); field("itemTitle",n["settingsItemClass"],"e","Ljava/lang/CharSequence;")
            method("itemClick",n["settingsClickClass"],"onClick",("Landroid/view/View;",))
        elif id_ == "drawer" and n.get("nativeDrawer"):
            owner=n["drawerActionClass"]; field("drawerProviderField",owner,n["drawerProviderField"]); method("drawerConstructor",owner,"<init>",None)
            method("drawerTitleMethod",owner,n["drawerTitleMethod"],("Ljava/lang/String;",)); method("drawerClickMethod",owner,n["drawerClickMethod"],("Landroid/view/View;",))
            for m in n["drawerBindMethods"]: method("drawerBind."+m,"com.dragon.read.component.shortvideo.impl.moredialog.ShortSeriesMorePanelDialogV2$b",m,("Ljava/lang/Object;" if m=="onBind" else "Lcom/dragon/read/component/shortvideo/impl/moredialog/action/o;","I"))
            resource(n["drawerTitleResource"])
        elif id_ == "ads":
            method("adVideoEndShowMethod","com.dragon.read.pages.video.layers.advideoendlayer.AdVideoEndLayer",n["adVideoEndShowMethod"])
            method("pauseAdEntryMethod",n["pauseAdEntryClass"],n["pauseAdEntryMethod"],("Landroid/view/View;","Z"))
        elif id_ == "brightness":
            cls("oledBrightAction",n["oledBrightAction"]); method("oledBright",n["oledBright"],"a",("Ljava/util/List;",))
        elif id_ == "membership":
            cls("kmpVipModel",n["kmpVipModel"])
            for i,owner in enumerate(n["kmpAcctService"]): method("getVipInfo."+str(i),owner,"getVipInfo",result=obj(n["kmpVipModel"]))
        titles = dict(playback="播放与暂停状态",playback_layout="播放布局恢复",controls="精简控件与透明度",quality="默认画质",speed="默认倍速",comments="双击评论",settings="红果设置入口",drawer="长按播放抽屉",ads="播放广告入口",brightness="OLED保护映射",membership="会员模型映射")
        feature=dict(id=id_,title=titles[id_],contract=id_+"-v1",outcome="PASS",mappings={k:mappings[k] for k in keys},delegates=delegates)
        if not delegates: feature["outcome"]="UNSUPPORTED"
        if any(d["status"]!="SUCCESS" for d in delegates): feature["outcome"]="UNVERIFIED"
        features.append(feature)
    unknown=set(n)-set(mappings)-{"profileId","packageName","versionName"}
    if unknown: raise ValueError("unassigned mappings: "+str(unknown))
    return dict(schemaVersion=2,hookContract="guoplus-hooks-v1",revision=root["revision"]+1,versionCode=root["versionCode"],profileId=n["profileId"],host=dict(packageName=n["packageName"],versionName=n["versionName"]),features=features)


if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    for flag in ("profile","dex-index","resources","output"): parser.add_argument("--"+flag,type=Path,required=flag in ("profile","output"))
    args=parser.parse_args(); result=generate(args.profile,args.dex_index,args.resources)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(args.output,[(f["id"],f["outcome"],len(f["delegates"])) for f in result["features"]])
