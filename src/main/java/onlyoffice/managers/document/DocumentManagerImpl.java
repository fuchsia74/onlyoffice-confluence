/**
 *
 * (c) Copyright Ascensio System SIA 2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package onlyoffice.managers.document;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.atlassian.confluence.core.ContentEntityManager;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.languages.LocaleManager;
import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.spring.container.ContainerManager;
import onlyoffice.managers.convert.ConvertManager;
import onlyoffice.utils.attachment.AttachmentUtil;
import onlyoffice.managers.configuration.ConfigurationManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.json.JSONObject;

import javax.enterprise.inject.Default;
import javax.inject.Inject;
import javax.inject.Named;

@Named
@Default
public class DocumentManagerImpl implements DocumentManager {
    private final Logger log = LogManager.getLogger("onlyoffice.managers.document.DocumentManager");

    @ComponentImport
    private final I18nResolver i18n;
    private final ConfigurationManager configurationManager;
    private final AttachmentUtil attachmentUtil;
    private final ConvertManager convertManager;

    @Inject
    public DocumentManagerImpl(I18nResolver i18n, ConfigurationManager configurationManager,
                               AttachmentUtil attachmentUtil, ConvertManager convertManager) {
        this.i18n = i18n;
        this.configurationManager = configurationManager;
        this.attachmentUtil = attachmentUtil;
        this.convertManager = convertManager;
    }

    public long getMaxFileSize() {
        long size;
        try {
            String filesizeMax = configurationManager.getProperty("filesize-max");
            size = Long.parseLong(filesizeMax);
        } catch (Exception ex) {
            size = 0;
        }

        return size > 0 ? size : 5 * 1024 * 1024;
    }

    public List<String> getEditedExts() {
        String exts = configurationManager.getProperty("files.docservice.edited-docs");
        if(exts == null) return new ArrayList<String>();
        return Arrays.asList(exts.split("\\|"));
    }

    public List<String> getFillFormExts() {
        String exts = configurationManager.getProperty("files.docservice.fill-docs");
        if(exts == null) return new ArrayList<String>();
        return Arrays.asList(exts.split("\\|"));
    }

    public String getKeyOfFile(Long attachmentId) {
        String hashCode = attachmentUtil.getHashCode(attachmentId);

        return generateRevisionId(hashCode);
    }

    private String generateRevisionId(String expectedKey) {
        if (expectedKey.length() > 20) {
            expectedKey = Integer.toString(expectedKey.hashCode());
        }
        String key = expectedKey.replace("[^0-9-.a-zA-Z_=]", "_");
        key = key.substring(0, Math.min(key.length(), 20));
        log.info("key = " + key);
        return key;
    }

    public String getCorrectName(String fileName, String fileExt, Long pageID) {
        ContentEntityManager contentEntityManager = (ContentEntityManager) ContainerManager.getComponent("contentEntityManager");
        AttachmentManager attachmentManager = (AttachmentManager) ContainerManager.getComponent("attachmentManager");
        ContentEntityObject contentEntityObject = contentEntityManager.getById(pageID);

        List<Attachment> Attachments  =  attachmentManager.getLatestVersionsOfAttachments(contentEntityObject);
        String name = (fileName + "." + fileExt).replaceAll("[*?:\"<>/|\\\\]","_");
        int count = 0;
        Boolean flag = true;

        while(flag) {
            flag = false;
            for (Attachment attachment : Attachments) {
                if (attachment.getFileName().equals(name)) {
                    count++;
                    name = fileName + " (" + count + ")." + fileExt;
                    flag = true;
                    break;
                }
            }
        }

        return name;
    }

    private InputStream getDemoFile(ConfluenceUser user, String fileExt) {
        LocaleManager localeManager = (LocaleManager) ContainerManager.getComponent("localeManager");
        PluginAccessor pluginAccessor = (PluginAccessor) ContainerManager.getComponent("pluginAccessor");

        String pathToDemoFile = "app_data/" + localeManager.getLocale(user).toString().replace("_", "-");

        if (pluginAccessor.getDynamicResourceAsStream(pathToDemoFile) == null) {
            pathToDemoFile = "app_data/en-US";
        }

        return pluginAccessor.getDynamicResourceAsStream(pathToDemoFile + "/new." + fileExt);
    }

    public Long createDemo(String fileName, String fileExt, Long pageID, String mimeType, String attachmentTemplateId) {
        Attachment attachment = null;
        try {
            ConfluenceUser confluenceUser = AuthenticatedUserThreadLocal.get();
            PageManager pageManager = (PageManager) ContainerManager.getComponent("pageManager");
            AttachmentManager attachmentManager = (AttachmentManager) ContainerManager.getComponent("attachmentManager");

            fileExt = fileExt == null || !fileExt.equals("xlsx") && !fileExt.equals("pptx") && !fileExt.equals("docxf") ? "docx" : fileExt.trim();
            fileName = fileName == null || fileName.equals("") ? i18n.getText("onlyoffice.connector.dialog-filecreate." + fileExt) : fileName;

            Date date = Calendar.getInstance().getTime();

            InputStream inputStream = null;
            long size = 0;

            if (fileExt.equals("docxf") && attachmentTemplateId != null && !attachmentTemplateId.equals("")) {
                Long attachmentTemplateIdAsLong = Long.parseLong(attachmentTemplateId);

                if (!attachmentUtil.checkAccess(attachmentTemplateIdAsLong, AuthenticatedUserThreadLocal.get(), false)) {
                    throw new SecurityException("You don not have enough permission to read the file");
                }

                String attachmentTemplateExt = attachmentUtil.getAttachmentExt(attachmentTemplateIdAsLong);

                if (!attachmentTemplateExt.equals("docx")) {
                    throw new RuntimeException("Template format does not match docx format");
                }

                String key = getKeyOfFile(attachmentTemplateIdAsLong);

                JSONObject convertResponse = convertManager.convert(attachmentTemplateIdAsLong, key, attachmentTemplateExt, "docxf", false);
                String urlToDOCXF = convertResponse.getString("fileUrl");

                try (CloseableHttpClient httpClient = configurationManager.getHttpClient()) {
                    HttpGet request = new HttpGet(urlToDOCXF);

                    try (CloseableHttpResponse response = httpClient.execute(request)) {

                        int status = response.getStatusLine().getStatusCode();
                        HttpEntity entity = response.getEntity();

                        if (status == HttpStatus.SC_OK) {
                            byte[] bytes = IOUtils.toByteArray(entity.getContent());
                            inputStream = new ByteArrayInputStream(bytes);
                            size = bytes.length;
                        } else {
                            throw new HttpException("Document Server returned code " + status);
                        }
                    }
                }
            } else {
                inputStream = getDemoFile(confluenceUser, fileExt);
                size = inputStream.available();
            }

            fileName = getCorrectName(fileName, fileExt, pageID);

            Page page = pageManager.getPage(pageID);
            attachment = new Attachment(fileName, mimeType, size, "");

            attachment.setCreator(confluenceUser);
            attachment.setCreationDate(date);
            attachment.setLastModificationDate(date);
            attachment.setContainer(pageManager.getPage(pageID));

            attachmentManager.saveAttachment(attachment, null, inputStream);
            page.addAttachment(attachment);
        } catch (Exception ex) {
            log.error(ex.getMessage(), ex);
        }

        return attachment.getContentId().asLong();
    }

    public String getDocType(String ext) {
        if (".doc.docx.docm.dot.dotx.dotm.odt.fodt.ott.rtf.txt.html.htm.mht.pdf.djvu.fb2.epub.xps.docxf.oform".indexOf(ext) != -1)
            return "text";
        if (".xls.xlsx.xlsm.xlt.xltx.xltm.ods.fods.ots.csv".indexOf(ext) != -1)
            return "spreadsheet";
        if (".pps.ppsx.ppsm.ppt.pptx.pptm.pot.potx.potm.odp.fodp.otp".indexOf(ext) != -1)
            return "presentation";
        return null;
    }

    public String getMimeType(String name) {
        Path path = new File(name).toPath();
        String mimeType = null;
        try {
            mimeType = Files.probeContentType(path);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return mimeType != null ? mimeType : "application/octet-stream";
    }
}
