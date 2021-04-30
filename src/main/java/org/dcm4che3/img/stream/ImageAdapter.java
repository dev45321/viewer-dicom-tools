/*
 * Copyright (c) 2009-2021 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.dcm4che3.img.stream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Objects;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR.Holder;
import org.dcm4che3.imageio.codec.TransferSyntaxType;
import org.dcm4che3.imageio.codec.jpeg.JPEGParser;
import org.dcm4che3.img.DicomImageReader;
import org.dcm4che3.img.DicomOutputData;
import org.dcm4che3.img.DicomTranscodeParam;
import org.dcm4che3.img.Transcoder;
import org.dcm4che3.img.util.DicomUtils;
import org.dcm4che3.img.util.Editable;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.DataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.param.AttributeEditorContext;
import org.weasis.dicom.web.Payload;
import org.weasis.opencv.data.PlanarImage;

public class ImageAdapter {
  private static final Logger LOGGER = LoggerFactory.getLogger(ImageAdapter.class);

  protected static final byte[] EMPTY_BYTES = {};

  private ImageAdapter() {}

  public static DataWriter buildDataWriter(
      Attributes data,
      String supportedTsuid,
      Editable<PlanarImage> editable,
      BytesWithImageDescriptor desc)
      throws IOException {
    if (desc == null) {
      return (out, tsuid) -> {
        try (DicomOutputStream writer = new DicomOutputStream(out, tsuid)) {
          writer.writeDataset(null, data);
          writer.finish();
        }
      };
    }

    DicomTranscodeParam tparams = new DicomTranscodeParam(supportedTsuid);
    DicomImageReader reader = new DicomImageReader(Transcoder.dicomImageReaderSpi);
    DicomOutputData imgData = geDicomOutputData(reader, tparams, desc, editable);

    return (out, tsuid) -> {
      Attributes dataSet = new Attributes(data);
      dataSet.remove(Tag.PixelData);
      try (DicomOutputStream dos = new DicomOutputStream(out, supportedTsuid)) {
        if (DicomOutputData.isNativeSyntax(supportedTsuid)) {
          imgData.writRawImageData(dos, dataSet);
        } else {
          int[] jpegWriteParams =
              imgData.adaptTagsToCompressedImage(
                  dataSet,
                  imgData.getFistImage().get(),
                  desc.getImageDescriptor(),
                  tparams.getWriteJpegParam());
          imgData.writCompressedImageData(dos, dataSet, jpegWriteParams);
        }
      } catch (Exception e) {
        LOGGER.error("Transcoding image data", e);
      } finally {
        reader.dispose();
      }
    };
  }

  public static BytesWithImageDescriptor imageTranscode(
      Attributes data,
      String originalTsuid,
      String supportedTsuid,
      AttributeEditorContext context) {

    Holder pixeldataVR = new Holder();
    Object pixdata = data.getValue(Tag.PixelData, pixeldataVR);
    if ((Objects.nonNull(context.getMaskArea())
            && pixdata != null
            && !DicomUtils.isVideo(originalTsuid))
        || (!supportedTsuid.equals(originalTsuid)
            && TransferSyntaxType.forUID(originalTsuid) != TransferSyntaxType.NATIVE)) {

      ImageDescriptor imdDesc = new ImageDescriptor(data);
      ByteBuffer[] mfByteBuffer = new ByteBuffer[1];
      ArrayList<Integer> fragmentsPositions = new ArrayList<>();
      return new BytesWithImageDescriptor() {

        @Override
        public ImageDescriptor getImageDescriptor() {
          return imdDesc;
        }

        @Override
        public ByteBuffer getBytes(int frame) throws IOException {
          ImageDescriptor desc = getImageDescriptor();
          int bitsStored = desc.getBitsStored();
          if (pixdata == null || bitsStored < 1) {
            return ByteBuffer.wrap(EMPTY_BYTES);
          } else {
            Fragments fragments = null;
            BulkData bulkData = null;
            boolean bigendian = false;
            if (pixdata instanceof BulkData) {
              bulkData = (BulkData) pixdata;
              bigendian = bulkData.bigEndian();
              //            } else if (data.getString(Tag.PixelDataProviderURL) != null) {
              //              // TODO Handle JPIP
              //              // always little endian:
              //              //
              // http://dicom.nema.org/medical/dicom/2017b/output/chtml/part05/sect_A.6.html
            } else if (pixdata instanceof Fragments) {
              fragments = (Fragments) pixdata;
              bigendian = fragments.bigEndian();
            }

            boolean hasFragments = fragments != null;
            if (!hasFragments && bulkData != null) {
              int frameLength =
                  desc.getPhotometricInterpretation()
                      .frameLength(
                          desc.getColumns(),
                          desc.getRows(),
                          desc.getSamples(),
                          desc.getBitsAllocated());
              if (mfByteBuffer[0] == null) {
                mfByteBuffer[0] = ByteBuffer.wrap(bulkData.toBytes(pixeldataVR.vr, bigendian));
              }

              if (mfByteBuffer[0].limit() < frame * frameLength + frameLength) {
                throw new IOException("Frame out of the stream limit");
              }

              byte[] bytes = new byte[frameLength];
              mfByteBuffer[0].position(frame * frameLength);
              mfByteBuffer[0].get(bytes, 0, frameLength);
              return ByteBuffer.wrap(bytes);
            } else if (hasFragments) {
              int nbFragments = fragments.size();
              int numberOfFrame = desc.getFrames();
              if (numberOfFrame == 1) {
                int length = 0;
                for (int i = 0; i < nbFragments - 1; i++) {
                  BulkData b = (BulkData) fragments.get(i + 1);
                  length += b.length();
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream(length);
                for (int i = 0; i < nbFragments - 1; i++) {
                  BulkData b = (BulkData) fragments.get(i + 1);
                  byte[] bytes = b.toBytes(pixeldataVR.vr, bigendian);
                  out.write(bytes, 0, bytes.length);
                }
                return ByteBuffer.wrap(out.toByteArray());
              } else {
                // Multi-frames where each frames can have multiple fragments.
                if (fragmentsPositions.isEmpty()) {
                  if (UID.RLELossless.equals(originalTsuid)) {
                    for (int i = 1; i < nbFragments; i++) {
                      fragmentsPositions.add(i);
                    }
                  } else {
                    for (int i = 1; i < nbFragments; i++) {
                      BulkData b = (BulkData) fragments.get(i);
                      try (ByteArrayOutputStream out = new ByteArrayOutputStream(b.length())) {
                        byte[] bytes = b.toBytes(pixeldataVR.vr, bigendian);
                        out.write(bytes, 0, bytes.length);
                        try (SeekableInMemoryByteChannel channel =
                            new SeekableInMemoryByteChannel(out.toByteArray())) {
                          new JPEGParser(channel);
                          fragmentsPositions.add(i);
                        }
                      } catch (Exception e) {
                        // Not jpeg stream
                      }
                    }
                  }
                }

                if (fragmentsPositions.size() == numberOfFrame) {
                  int start = fragmentsPositions.get(frame);
                  int end =
                      (frame + 1) >= fragmentsPositions.size()
                          ? nbFragments
                          : fragmentsPositions.get(frame + 1);

                  int length = 0;
                  for (int i = 0; i < end - start; i++) {
                    BulkData b = (BulkData) fragments.get(start + i);
                    length += b.length();
                  }
                  ByteArrayOutputStream out = new ByteArrayOutputStream(length);
                  for (int i = 0; i < end - start; i++) {
                    BulkData b = (BulkData) fragments.get(start + i);
                    byte[] bytes = b.toBytes(pixeldataVR.vr, bigendian);
                    out.write(bytes, 0, bytes.length);
                  }
                  return ByteBuffer.wrap(out.toByteArray());
                } else {
                  throw new IOException("Cannot match all the fragments to all the frames!");
                }
              }
            }
          }
          throw new IOException("Neither fragments nor BulkData!");
        }

        @Override
        public String getTransferSyntax() {
          return originalTsuid;
        }

        @Override
        public Attributes getPaletteColorLookupTable() {
          Attributes dcm = new Attributes(10);
          copyValue(data, dcm, Tag.RedPaletteColorLookupTableDescriptor);
          copyValue(data, dcm, Tag.RedPaletteColorLookupTableDescriptor);
          copyValue(data, dcm, Tag.GreenPaletteColorLookupTableDescriptor);
          copyValue(data, dcm, Tag.BluePaletteColorLookupTableDescriptor);
          copyValue(data, dcm, Tag.RedPaletteColorLookupTableData);
          copyValue(data, dcm, Tag.GreenPaletteColorLookupTableData);
          copyValue(data, dcm, Tag.BluePaletteColorLookupTableData);
          copyValue(data, dcm, Tag.SegmentedRedPaletteColorLookupTableData);
          copyValue(data, dcm, Tag.SegmentedGreenPaletteColorLookupTableData);
          copyValue(data, dcm, Tag.SegmentedBluePaletteColorLookupTableData);
          return dcm;
        }
      };
    }
    return null;
  }

  private static void copyValue(Attributes original, Attributes copy, int tag) {
    if (original.containsValue(tag)) {
      copy.setValue(tag, original.getVR(tag), original.getValue(tag));
    }
  }

  public static Payload preparePlayload(
      Attributes data,
      String outputTsuid,
      BytesWithImageDescriptor desc,
      Editable<PlanarImage> editable)
      throws IOException {
    DicomTranscodeParam tparams = new DicomTranscodeParam(outputTsuid);
    DicomImageReader reader = new DicomImageReader(Transcoder.dicomImageReaderSpi);
    DicomOutputData imgData = geDicomOutputData(reader, tparams, desc, editable);
    Attributes dataSet = new Attributes(data);
    dataSet.remove(Tag.PixelData);

    return new Payload() {
      @Override
      public long size() {
        return -1;
      }

      @Override
      public InputStream newInputStream() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DicomOutputStream dos = new DicomOutputStream(out, outputTsuid)) {
          dos.writeFileMetaInformation(dataSet.createFileMetaInformation(outputTsuid));
          if (DicomOutputData.isNativeSyntax(outputTsuid)) {
            imgData.writRawImageData(dos, dataSet);
          } else {
            int[] jpegWriteParams =
                imgData.adaptTagsToCompressedImage(
                    dataSet,
                    imgData.getFistImage().get(),
                    desc.getImageDescriptor(),
                    tparams.getWriteJpegParam());
            imgData.writCompressedImageData(dos, dataSet, jpegWriteParams);
          }
        } catch (IOException e) {
          LOGGER.error("Cannot write DICOM", e);
          return new ByteArrayInputStream(new byte[] {});
        } finally {
          reader.dispose();
        }
        return new ByteArrayInputStream(out.toByteArray());
      }
    };
  }

  private static DicomOutputData geDicomOutputData(
      DicomImageReader reader,
      DicomTranscodeParam tparams,
      BytesWithImageDescriptor desc,
      Editable<PlanarImage> editable)
      throws IOException {
    reader.setInput(desc);
    var images = reader.getLazyPlanarImages(tparams.getReadParam(), editable);
    return new DicomOutputData(images, desc.getImageDescriptor(), tparams.getOutputTsuid());
  }
}