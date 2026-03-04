-- Function to strip Markdown syntax before building tsvector
CREATE OR REPLACE FUNCTION strip_markdown_and_update_search_vector()
RETURNS TRIGGER AS $$
DECLARE
    clean_title TEXT;
    clean_content TEXT;
BEGIN
    clean_title := NEW.title;
    clean_content := NEW.content;

    -- Strip Markdown headings (##, ###, etc.)
    clean_content := regexp_replace(clean_content, '#{1,6}\s+', '', 'g');
    -- Strip bold/italic (**text**, *text*, __text__, _text_)
    clean_content := regexp_replace(clean_content, '\*{1,2}([^*]+)\*{1,2}', '\1', 'g');
    clean_content := regexp_replace(clean_content, '_{1,2}([^_]+)_{1,2}', '\1', 'g');
    -- Strip inline code (`code`)
    clean_content := regexp_replace(clean_content, '`([^`]+)`', '\1', 'g');
    -- Strip code fences (```...```)
    clean_content := regexp_replace(clean_content, '```[\s\S]*?```', '', 'g');
    -- Strip links [text](url) → text
    clean_content := regexp_replace(clean_content, '\[([^\]]+)\]\([^)]+\)', '\1', 'g');
    -- Strip images ![alt](url)
    clean_content := regexp_replace(clean_content, '!\[([^\]]*)\]\([^)]+\)', '\1', 'g');

    NEW.search_vector :=
        setweight(to_tsvector('english', COALESCE(clean_title, '')), 'A') ||
        setweight(to_tsvector('english', COALESCE(clean_content, '')), 'B');

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_blog_post_search_vector ON blog_post;
CREATE TRIGGER trg_blog_post_search_vector
    BEFORE INSERT OR UPDATE OF title, content ON blog_post
    FOR EACH ROW
    EXECUTE FUNCTION strip_markdown_and_update_search_vector();
