package onlyoffice.utils.attachment;

import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.user.User;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

public interface AttachmentUtil extends Serializable {
    public boolean checkAccess(Long attachmentId, User user, boolean forEdit);
    public boolean checkAccess(Attachment attachment, User user, boolean forEdit);
    public void saveAttachment(Long attachmentId, InputStream attachmentData, int size, ConfluenceUser user)
            throws IOException, IllegalArgumentException;
    public boolean checkAccessCreate(User user, Long pageId);
    public InputStream getAttachmentData(Long attachmentId);
    public String getMediaType(Long attachmentId);
    public String getFileName(Long attachmentId);
    public String getHashCode(Long attachmentId);
    public String getAttachmentPageTitle (Long attachmentId);
    public Long getAttachmentPageId (Long attachmentId);
    public String getAttachmentSpaceName (Long attachmentId);
    public String getAttachmentSpaceKey (Long attachmentId);
    public Attachment createNewAttachment (String title, String mimeType, InputStream file, int size, Long pageId, ConfluenceUser user) throws IOException;
    public String getAttachmentExt (Long attachmentId);
    public String createHash(String str);
    public String readHash(String base64);
}
