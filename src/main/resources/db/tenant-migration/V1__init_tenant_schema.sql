CREATE SEQUENCE IF NOT EXISTS categorie_article_id_seq START WITH 1 INCREMENT BY 1;

CREATE SEQUENCE IF NOT EXISTS groupe_article_id_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE article_categorie
(
    id_categorie_article BIGINT NOT NULL,
    code_categorie       VARCHAR(255),
    libelle              VARCHAR(255),
    fk_groupe_article    BIGINT,
    id_presta_shop       BIGINT,
    created_at           TIMESTAMP WITHOUT TIME ZONE,
    updated_at           TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_article_categorie PRIMARY KEY (id_categorie_article)
);

CREATE TABLE article_groupe
(
    id_groupe_article   BIGINT NOT NULL,
    code_groupe_article VARCHAR(255),
    libelle             VARCHAR(255),
    id_presta_shop      BIGINT,
    created_at          TIMESTAMP WITHOUT TIME ZONE,
    updated_at          TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_article_groupe PRIMARY KEY (id_groupe_article)
);

ALTER TABLE article_groupe
    ADD CONSTRAINT uc_article_groupe_codegroupearticle UNIQUE (code_groupe_article);

ALTER TABLE article_categorie
    ADD CONSTRAINT FK_GROUPE_ARTICLE FOREIGN KEY (fk_groupe_article) REFERENCES article_groupe (id_groupe_article);