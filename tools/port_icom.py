# Copies FT8CN's Icom network-protocol classes (MIT; com.bg7yoz.ft8cn.icom) into the app as uk.hamdigital.icom and
# makes the few changes listed in app/src/main/java/uk/hamdigital/icom/ANDROID_CHANGES.txt (the protocol code itself is
# unchanged). Usage: python tools/port_icom.py <FT8CN icom folder> <app icom folder>
import os, sys                                       # files

src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
files = ["AudioUdp", "ControlUdp", "IComPacketTypes", "IComWifiRig", "IcomAudioUdp", "IcomCivUdp", "IcomControlUdp",
         "IcomSeqBuffer", "IcomUdpBase", "IcomUdpClient", "WifiRig"] # (the Xiegu classes are not needed)

def edit(s, old, new):                               # replace exactly once, or stop
    if s.count(old) != 1: sys.exit(f"expected one {old[:70]!r}")
    return s.replace(old, new)

for name in files:
    s = open(os.path.join(src, name + ".java"), encoding="utf-8").read()
    s = s.replace("package com.bg7yoz.ft8cn.icom;", "package uk.hamdigital.icom;")
    s = s.replace("com.bg7yoz.ft8cn.icom.", "uk.hamdigital.icom.")
    s = s.replace("import com.bg7yoz.ft8cn.GeneralVariables;\n", "").replace("import com.bg7yoz.ft8cn.R;\n", "")
    s = s.replace("import com.bg7yoz.ft8cn.ui.ToastMessage;\n", "").replace("import org.checkerframework.checker.units.qual.A;\n", "")
    s = s.replace("import com.bg7yoz.ft8cn.rigs.IcomRigConstant;\n", "")
    if name == "IcomUdpBase":                        # stream names in English, without FT8CN's string resources
        s = edit(s, "return GeneralVariables.getStringFromResource(R.string.control_stream);", 'return "control";')
        s = edit(s, "return GeneralVariables.getStringFromResource(R.string.civ_stream);", 'return "CI-V";')
        s = edit(s, "return GeneralVariables.getStringFromResource(R.string.audio_stream);", 'return "audio";')
        s = edit(s, "return GeneralVariables.getStringFromResource(R.string.data_stream);", 'return "data";')
    if name == "IcomAudioUdp":                       # the app sets its own transmit level: no extra scaling
        s = edit(s, "* GeneralVariables.volumePercent))", "* 1.0f))")
    if name == "IcomCivUdp":                         # PTT: CI-V 1C 00 01 / 00, built here (was FT8CN's IcomRigConstant)
        s = edit(s, "sendCivData(IcomRigConstant.setPTTState(0xe0, civAddress, IcomRigConstant.PTT_ON));",
                 "sendCivData(new byte[]{(byte) 0xfe, (byte) 0xfe, civAddress, (byte) 0xe0, 0x1c, 0x00, 0x01, (byte) 0xfd});")
        s = edit(s, "sendCivData(IcomRigConstant.setPTTState(0xe0, civAddress, IcomRigConstant.PTT_OFF));",
                 "sendCivData(new byte[]{(byte) 0xfe, (byte) 0xfe, civAddress, (byte) 0xe0, 0x1c, 0x00, 0x00, (byte) 0xfd});")
    if name == "IComWifiRig":                        # messages to the app instead of FT8CN's toasts; no playback on the phone
        s = edit(s, "        openAudio();", "        // (HF Digital Modes: the radio's audio goes to the decoders, not the phone's speaker)")
        s = edit(s, """                ToastMessage.show(String.format(GeneralVariables.getStringFromResource(
                        R.string.network_exception),IcomUdpBase.getUdpStyle(style),e.getMessage()));""",
                 """                if (onStatus != null) onStatus.onStatus("Network error on the " + IcomUdpBase.getUdpStyle(style) + " stream: " + e.getMessage(), false);""")
        s = edit(s, "ToastMessage.show(GeneralVariables.getStringFromResource(R.string.login_succeed));",
                 'if (onStatus != null) onStatus.onStatus("Logged in to the radio", true);')
        s = edit(s, "ToastMessage.show(GeneralVariables.getStringFromResource(R.string.loging_failed));",
                 'if (onStatus != null) onStatus.onStatus("The radio refused the user name or password", false);')
    if name == "WifiRig":                            # the status callback the app listens to
        s = edit(s, "    public OnDataEvents onDataEvents;", """    public OnDataEvents onDataEvents;
    /** HF Digital Modes: login and network messages for the app (FT8CN showed them as toasts). */
    public interface OnStatus { void onStatus(String message, boolean ok); }
    public OnStatus onStatus;""")
    if "GeneralVariables" in s or "ToastMessage" in s or "R.string" in s: sys.exit(f"{name}: FT8CN references left")
    open(os.path.join(dst, name + ".java"), "w", encoding="utf-8", newline="\n").write(s)
print("ok", len(files))
