/*
 * Copyright (c) 2023.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the “Software”), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.somewhatcity.boiler.core.sources;

import com.google.gson.JsonObject;
import net.somewhatcity.boiler.api.CreateArgument;
import net.somewhatcity.boiler.api.CreateCommandArguments;
import net.somewhatcity.boiler.api.IBoilerSource;
import net.somewhatcity.boiler.api.display.IBoilerDisplay;
import net.somewhatcity.boiler.api.util.CommandArgumentType;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Base64;

@CreateCommandArguments(arguments = {
        @CreateArgument(name = "url", type = CommandArgumentType.GREEDY_STRING)
})
public class ImageSource implements IBoilerSource, CancellableLoad {
    // Bounds protect the server heap from huge downloads and decompression bombs.
    private static final int MAX_DOWNLOAD = 32 * 1024 * 1024, MAX_SIDE = 4096, MAX_STORED = 2 * 1024 * 1024;
    private BufferedImage image;
    private volatile okhttp3.Call call;
    private volatile boolean cancelled;
    @Override public void cancelLoad() {
        cancelled = true;
        if (call != null) call.cancel();
    }
    @Override
    public void load(IBoilerDisplay display, JsonObject data) {
        JsonObject obj = new JsonObject();

        if(data == null) {
            obj.addProperty("message", "no error provided");
            display.source("error", obj);
            return;
        }

        try {
            String url = data.get("url").getAsString();
            if(url.startsWith("file:")) {
                url = url.substring(5);
                File imageFile = new File(url);
                if(imageFile.exists() && imageFile.isFile()) {
                    image = read(imageFile);
                } else {
                    obj.addProperty("message", "File not found");
                    display.source("error", obj);
                    return;
                }
            } else {
                if(data.has("base64")) {
                    String base64EncodedImage = data.get("base64").getAsString();
                    byte[] imageData = Base64.getDecoder().decode(base64EncodedImage);
                    ByteArrayInputStream bais = new ByteArrayInputStream(imageData);
                    image = read(bais);

                } else {
                    call = net.somewhatcity.boiler.core.BoilerPlugin.getPlugin().runtime().http().newCall(new okhttp3.Request.Builder().url(url).build());
                    if (cancelled || Thread.currentThread().isInterrupted()) call.cancel();
                    try (okhttp3.Response response = call.execute()) {
                        if (!response.isSuccessful() || response.body() == null) throw new IOException("Image request failed: " + response.code());
                        if (response.body().contentLength() > MAX_DOWNLOAD) throw new IOException("Image is too large");
                        byte[] downloaded = response.body().byteStream().readNBytes(MAX_DOWNLOAD + 1);
                        if (downloaded.length > MAX_DOWNLOAD) throw new IOException("Image is too large");
                        image = read(new ByteArrayInputStream(downloaded));
                    }
                    if (image == null) throw new IOException("Unsupported image format");

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    boolean success = ImageIO.write(image, "jpg", baos);
                    if(!success) ImageIO.write(image, "png", baos);
                    byte[] imageData = baos.toByteArray();
                    String base64EncodedImage = Base64.getEncoder().encodeToString(imageData);

                    // Large images are fetched again on load instead of bloating the display file.
                    if (base64EncodedImage.length() <= MAX_STORED) {
                        data.addProperty("base64", base64EncodedImage);
                        display.saveSourceData(data);
                    }
                }
            }
            if (image == null) throw new IOException("Unsupported image format");
        } catch (IOException e) {
            obj.addProperty("message",e.getMessage());
            display.source("error", obj);
        }
    }

    private static BufferedImage read(Object input) throws IOException {
        try (javax.imageio.stream.ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            if (stream == null) throw new IOException("Unsupported image format");
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Unsupported image format");
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) throw new IOException("Image is too large");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    @Override
    public void unload() {
        cancelLoad();

    }

    @Override
    public void draw(Graphics2D g2, Rectangle viewport) {
        g2.drawImage(image, 0, 0, viewport.width, viewport.height, null);
    }

}
