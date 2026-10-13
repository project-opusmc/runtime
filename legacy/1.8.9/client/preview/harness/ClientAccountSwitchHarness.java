package org.polydevs.opusmc.client;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.Session;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.properties.Property;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
/** Actual mapped session write, without launching a game or contacting OAuth. */
public final class ClientAccountSwitchHarness {
 public static void main(String[] args) throws Exception {
  Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);sun.misc.Unsafe unsafe=(sun.misc.Unsafe)unsafeField.get(null);
  Minecraft mc=(Minecraft)unsafe.allocateInstance(Minecraft.class);
  Field session=ReflectionHelper.findField(Minecraft.class,"session","field_71449_j"),properties=ReflectionHelper.findField(Minecraft.class,"profileProperties","field_181038_N");
  Session initial=new Session("First","11111111111111111111111111111111","0","legacy");PropertyMap oldProperties=new PropertyMap();oldProperties.put("textures",new Property("textures","test-old-profile"));
  session.set(mc,initial);properties.set(mc,oldProperties);
  Session next=ClientAccountSwitch.resolve("offline:second","Second","offline");ClientAccountSwitch.apply(mc,next);
  if(mc.getSession()!=next||!"Second".equals(mc.getSession().getUsername())||!oldProperties.isEmpty())throw new AssertionError("Running session/profile cache did not switch");
  mc.theWorld=(WorldClient)unsafe.allocateInstance(WorldClient.class);boolean rejected=false;
  try{ClientAccountSwitch.apply(mc,initial);}catch(IllegalStateException expected){rejected=true;}
  if(!rejected||mc.getSession()!=next)throw new AssertionError("Loaded world must preserve its account");mc.theWorld=null;rejected=false;
  try{ClientAccountSwitch.resolve("microsoft:missing","Missing","microsoft");}catch(IllegalStateException expected){rejected=true;}
  if(!rejected||mc.getSession()!=next)throw new AssertionError("Missing credentials must preserve session");
  System.out.println("PASS: mapped live account switch, profile cache reset, loaded-world rejection, missing-credential retention");
 }
}
