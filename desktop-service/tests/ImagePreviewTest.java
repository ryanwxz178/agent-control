package dev.orca.pocket;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

public final class ImagePreviewTest {
    private static byte[] png(BufferedImage image)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        if(!ImageIO.write(image,"png",output))throw new AssertionError("PNG encoder unavailable");
        return output.toByteArray();
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void rejects(byte[] input,int budget,String reason)throws Exception{
        try{TaskService.previewImage(input,budget);throw new AssertionError("Expected rejection: "+reason);}
        catch(IOException expected){check(expected.getMessage()!=null&&!expected.getMessage().isEmpty(),"Rejection explains the failure");}
    }
    private static byte[] replaceDimensions(byte[] original,int width,int height){
        byte[] result=original.clone();
        for(int i=0;i<4;i++){result[16+i]=(byte)(width>>>(24-8*i));result[20+i]=(byte)(height>>>(24-8*i));}
        CRC32 crc=new CRC32();crc.update(result,12,17);long value=crc.getValue();
        for(int i=0;i<4;i++)result[29+i]=(byte)(value>>>(24-8*i));
        return result;
    }
    public static void main(String[] args)throws Exception{
        byte[] tiny=png(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB));
        check(TaskService.previewImage(tiny,65536)==tiny,"Small image preserves original bytes and format");
        BufferedImage source=new BufferedImage(1800,1200,BufferedImage.TYPE_INT_ARGB);Random random=new Random(413);
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)source.setRGB(x,y,0xff000000|random.nextInt(0x1000000));
        Graphics2D graphics=source.createGraphics();
        graphics.setColor(Color.RED);graphics.fillRect(300,350,300,500);graphics.setColor(Color.BLUE);graphics.fillOval(1200,350,300,500);graphics.dispose();
        for(int y=0;y<250;y++)for(int x=0;x<350;x++)source.setRGB(x,y,0);
        byte[] original=png(source),unchanged=original.clone();source.flush();
        check(original.length>763*1024,"Fixture exceeds the user's 763 KB image");
        int total=0;
        for(int i=0;i<4;i++){
            byte[] preview=TaskService.previewImage(original,65536);total+=preview.length;
            check(preview.length<=65536,"Each of four previews stays within its 64 KiB budget");
            check((preview[0]&255)==255&&(preview[1]&255)==216&&(preview[2]&255)==255,"Large preview is JPEG");
            BufferedImage result=ImageIO.read(new ByteArrayInputStream(preview));
            check(result!=null&&Math.max(result.getWidth(),result.getHeight())<=1280,"Preview decodes and longest edge is capped");
            Color red=new Color(result.getRGB(result.getWidth()/4,result.getHeight()/2));
            Color blue=new Color(result.getRGB(result.getWidth()*3/4,result.getHeight()/2));
            Color white=new Color(result.getRGB(0,0));
            check(red.getRed()>180&&red.getBlue()<70,"Left red shape remains readable");
            check(blue.getBlue()>180&&blue.getRed()<70,"Right blue shape remains readable");
            check(white.getRed()>235&&white.getGreen()>235&&white.getBlue()>235,"Transparent image uses white JPEG background");
            result.flush();
        }
        check(total<=256*1024,"Four preview payloads share a 256 KiB aggregate budget");
        check(Arrays.equals(original,unchanged),"Original uploaded file remains byte-for-byte unchanged");
        byte[] jpegSource;BufferedImage jpegCanvas=new BufferedImage(1800,1200,BufferedImage.TYPE_INT_RGB);Graphics2D jpegGraphics=jpegCanvas.createGraphics();
        BufferedImage pngSource=ImageIO.read(new ByteArrayInputStream(original));jpegGraphics.setColor(Color.WHITE);jpegGraphics.fillRect(0,0,1800,1200);jpegGraphics.drawImage(pngSource,0,0,null);jpegGraphics.dispose();pngSource.flush();
        ByteArrayOutputStream jpegOutput=new ByteArrayOutputStream();ImageIO.write(jpegCanvas,"jpeg",jpegOutput);jpegCanvas.flush();jpegSource=jpegOutput.toByteArray();
        check(jpegSource.length>65536&&TaskService.previewImage(jpegSource,65536).length<=65536,"JPEG decoder uses bounded preview conversion");
        rejects(new byte[65537],65536,"invalid image");
        rejects(replaceDimensions(original,10001,10001),65536,"compressed source exceeds pixel limit before decoding");
        rejects(replaceDimensions(original,40000,1),65536,"compressed source exceeds dimension limit before decoding");
        rejects(Arrays.copyOf(original,100000),65536,"truncated image");
        rejects(original,1,"unrepresentable JPEG budget");
        rejects(original,0,"invalid budget");
        System.out.println("Image previews: original preservation, four-image budget, JPEG/PNG scaling, alpha matte, dimensions and malformed input passed");
    }
}
