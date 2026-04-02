package com.byf3332.radexcvr.cat

import com.bg7yoz.ft8cn.GeneralVariables
import com.bg7yoz.ft8cn.connector.ConnectMode
import com.bg7yoz.ft8cn.rigs.BaseRig
import com.bg7yoz.ft8cn.rigs.ElecraftRig
import com.bg7yoz.ft8cn.rigs.Flex6000Rig
import com.bg7yoz.ft8cn.rigs.FlexNetworkRig
import com.bg7yoz.ft8cn.rigs.GuoHeQ900Rig
import com.bg7yoz.ft8cn.rigs.IcomRig
import com.bg7yoz.ft8cn.rigs.InstructionSet
import com.bg7yoz.ft8cn.rigs.KenwoodKT90Rig
import com.bg7yoz.ft8cn.rigs.KenwoodTS2000Rig
import com.bg7yoz.ft8cn.rigs.KenwoodTS570Rig
import com.bg7yoz.ft8cn.rigs.KenwoodTS590Rig
import com.bg7yoz.ft8cn.rigs.TrUSDXRig
import com.bg7yoz.ft8cn.rigs.Wolf_sdr_450Rig
import com.bg7yoz.ft8cn.rigs.XieGu6100NetRig
import com.bg7yoz.ft8cn.rigs.XieGu6100Rig
import com.bg7yoz.ft8cn.rigs.XieGuRig
import com.bg7yoz.ft8cn.rigs.Yaesu2Rig
import com.bg7yoz.ft8cn.rigs.Yaesu2_847Rig
import com.bg7yoz.ft8cn.rigs.Yaesu38Rig
import com.bg7yoz.ft8cn.rigs.Yaesu38_450Rig
import com.bg7yoz.ft8cn.rigs.Yaesu39Rig
import com.bg7yoz.ft8cn.rigs.YaesuDX10Rig

object Ft8CnRigFactory {
    fun create(profile: Ft8CnRigProfile): BaseRig? {
        GeneralVariables.connectMode = ConnectMode.USB_CABLE
        GeneralVariables.instructionSet = profile.instructionSet
        return when (profile.instructionSet) {
            InstructionSet.ICOM -> IcomRig(profile.civAddress, true)
            InstructionSet.ICOM_756 -> IcomRig(profile.civAddress, false)
            InstructionSet.YAESU_2 -> Yaesu2Rig()
            InstructionSet.YAESU_847 -> Yaesu2_847Rig()
            InstructionSet.YAESU_3_9 -> Yaesu39Rig(false)
            InstructionSet.YAESU_3_9_U_DIG -> Yaesu39Rig(true)
            InstructionSet.YAESU_3_8 -> Yaesu38Rig()
            InstructionSet.YAESU_3_450 -> Yaesu38_450Rig()
            InstructionSet.KENWOOD_TK90 -> KenwoodKT90Rig()
            InstructionSet.YAESU_DX10 -> YaesuDX10Rig()
            InstructionSet.KENWOOD_TS590 -> KenwoodTS590Rig()
            InstructionSet.GUOHE_Q900 -> GuoHeQ900Rig()
            InstructionSet.XIEGUG90S -> XieGuRig(profile.civAddress)
            InstructionSet.ELECRAFT -> ElecraftRig()
            InstructionSet.FLEX_CABLE -> Flex6000Rig()
            InstructionSet.FLEX_NETWORK -> FlexNetworkRig()
            InstructionSet.XIEGU_6100 -> XieGu6100Rig(profile.civAddress)
            InstructionSet.XIEGU_6100_FT8CNS -> XieGu6100NetRig(profile.civAddress)
            InstructionSet.KENWOOD_TS2000 -> KenwoodTS2000Rig()
            InstructionSet.WOLF_SDR_DIGU -> Wolf_sdr_450Rig(false)
            InstructionSet.WOLF_SDR_USB -> Wolf_sdr_450Rig(true)
            InstructionSet.TRUSDX -> TrUSDXRig()
            InstructionSet.KENWOOD_TS570 -> KenwoodTS570Rig()
            else -> null
        }
    }
}
